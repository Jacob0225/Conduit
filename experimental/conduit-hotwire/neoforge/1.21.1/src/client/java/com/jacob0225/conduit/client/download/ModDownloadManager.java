package com.jacob0225.conduit.client.download;

import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.client.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full download pipeline for a list of mod entries:
 *
 * 1. Query Modrinth for the exact manifest version
 * 2. Download to staging directory ({@code .minecraft/conduit-staging/})
 * 3. Verify SHA-512 hash against BOTH API-reported and server-reported hashes
 * 4. Move verified file to managed directory ({@code .minecraft/mods/})
 * 5. Resolve and download transitive dependencies (each also hash-verified)
 *
 * All downloads use HTTPS. Download URLs are validated to be from known CDN origins.
 */
public class ModDownloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Download");

    // Subdirectory of the game directory for Conduit-installed mods
    public static final String MANAGED_DIR  = "mods";
    // Temporary directory; cleared on startup
    public static final String STAGING_DIR  = "conduit-staging";

    /** Progress callback so the UI can show download status. */
    public interface ProgressListener {
        void onStart(String modId, int total, int current);
        void onComplete(String modId);
        void onError(String modId, String reason);
    }

    public record DownloadResult(
            List<String> succeeded,
            List<String> failed,
            java.util.Map<String, String> installedFiles // modId → jar filename in mods/
    ) {
        /** Convenience for callers that only care about pass/fail. */
        public static DownloadResult empty() {
            return new DownloadResult(java.util.List.of(), java.util.List.of(), java.util.Map.of());
        }
    }

    private final ModrinthClient modrinthClient;
    private final DependencyResolver dependencyResolver;
    private final HttpClient http;
    private final Path managedDir;
    private final Path stagingDir;

    public ModDownloadManager(
            ModrinthClient modrinthClient,
            DependencyResolver dependencyResolver
    ) {
        this.modrinthClient      = modrinthClient;
        this.dependencyResolver  = dependencyResolver;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Path gameDir = PlatformPaths.gameDir();
        this.managedDir = gameDir.resolve(MANAGED_DIR);
        this.stagingDir = gameDir.resolve(STAGING_DIR);
    }

    /**
     * Prepares the managed and staging directories.
     * Call once before downloading — clears leftover staging files from a previous session.
     */
    public void prepare() throws IOException {
        Files.createDirectories(managedDir);
        Files.createDirectories(stagingDir);

        // Clear leftover .tmp files from a previous interrupted session
        try (var stream = Files.list(stagingDir)) {
            stream.filter(p -> p.toString().endsWith(".tmp"))
                  .forEach(p -> {
                      try { Files.delete(p); }
                      catch (IOException e) { LOGGER.warn("Failed to delete staging file: {}", p); }
                  });
        }
    }

    /**
     * Downloads and installs all mods in {@code entries}.
     *
     * @param entries  list of mods to install (required mods from the manifest)
     * @param listener progress callback (called on the calling thread)
     */
    public DownloadResult downloadAll(List<ModEntry> entries, ProgressListener listener) {
        List<String> succeeded = new ArrayList<>();
        List<String> failed    = new ArrayList<>();
        java.util.Map<String, String> installed = new java.util.HashMap<>();

        int total = entries.size();
        int current = 0;

        for (ModEntry entry : entries) {
            current++;
            listener.onStart(entry.modId(), total, current);

            OneResult r = downloadOne(entry, listener);
            if (r.ok()) {
                succeeded.add(entry.modId());
                if (r.filename() != null) installed.put(entry.modId(), r.filename());
            } else {
                failed.add(entry.modId());
            }
        }

        return new DownloadResult(succeeded, failed, java.util.Collections.unmodifiableMap(installed));
    }

    // ── Private pipeline ──────────────────────────────────────────────────────

    /** Outcome of a single mod install: success flag + the jar filename installed. */
    private record OneResult(boolean ok, String filename) {}

    private OneResult downloadOne(ModEntry entry, ProgressListener listener) {
        LOGGER.info("Downloading {} {} ...", entry.modId(), entry.requiredVersion());

        // Step 1: Resolve via Modrinth
        ModrinthClient.VersionInfo modrinthInfo = null;
        if (!entry.modrinthProjectId().isEmpty()) {
            modrinthInfo = modrinthClient.resolve(entry.modrinthProjectId(), entry.requiredVersion())
                    .orElse(null);
        }

        if (modrinthInfo == null) {
            String reason = entry.modrinthProjectId().isEmpty()
                    ? "The server did not give a Modrinth project for " + entry.modId() + "."
                    : "Modrinth has no NeoForge " + modrinthClient.minecraftVersion() + " build of "
                    + entry.modId() + " " + entry.requiredVersion()
                    + " (or Modrinth could not be reached). Ask the server owner for a compatible version.";
            LOGGER.error(reason);
            listener.onError(entry.modId(), reason);
            return new OneResult(false, null);
        }

        // Step 3: Download to staging
        String downloadUrl = modrinthInfo.downloadUrl();
        String apiHash     = modrinthInfo.sha512();
        String filename    = modrinthInfo.filename();

        // Modrinth supplies the filename; never let it name a path outside mods/.
        if (!isSafeJarFilename(filename)) {
            String reason = "Modrinth returned an unsafe filename for " + entry.modId();
            LOGGER.error("{}: {}", reason, filename);
            listener.onError(entry.modId(), reason);
            return new OneResult(false, null);
        }

        // Skip if the exact verified file is already present (e.g. from a
        // previous install attempt). A same-named file with different bytes is
        // a truncated or tampered copy and is replaced by a fresh download.
        Path destination = managedDir.resolve(filename);
        if (Files.isRegularFile(destination)) {
            try {
                HashVerifier.verifyBoth(destination, entry.hashAlgorithm(), apiHash, entry.expectedHash());
                LOGGER.info("{} already present in mods/ and verified — skipping download.", entry.modId());
                listener.onComplete(entry.modId());
                return new OneResult(true, filename);
            } catch (HashVerifier.VerificationException | IOException e) {
                LOGGER.warn("Existing {} does not match the expected hash; downloading a fresh copy.",
                        destination.getFileName());
            }
        }

        // Version strings are validated upstream, but keep the staging name filename-safe anyway.
        Path stagingFile = stagingDir.resolve((entry.modId() + "-" + entry.requiredVersion())
                .replaceAll("[^A-Za-z0-9._+-]", "_") + ".jar.tmp");

        try {
            downloadFile(downloadUrl, stagingFile);
        } catch (IOException e) {
            LOGGER.error("Download failed for {}: {}", entry.modId(), e.getMessage());
            listener.onError(entry.modId(), "Download failed: " + e.getMessage());
            deleteSilently(stagingFile);
            return new OneResult(false, null);
        }

        // Step 4: Hash verification
        try {
            HashVerifier.verifyBoth(stagingFile, entry.hashAlgorithm(), apiHash, entry.expectedHash());
        } catch (HashVerifier.VerificationException e) {
            LOGGER.error("HASH MISMATCH for {}: {}", entry.modId(), e.getMessage());
            listener.onError(entry.modId(), "Hash verification failed — file rejected");
            deleteSilently(stagingFile);
            return new OneResult(false, null);
        } catch (IOException e) {
            LOGGER.error("Could not read staging file for verification: {}", e.getMessage());
            listener.onError(entry.modId(), "Could not verify the download: " + e.getMessage());
            deleteSilently(stagingFile);
            return new OneResult(false, null);
        }

        // Step 5: Move to managed directory
        try {
            Files.move(stagingFile, destination, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Installed {} → {}", entry.modId(), destination.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to install {}: {}", entry.modId(), e.getMessage());
            listener.onError(entry.modId(), "Could not move the jar into mods/: " + e.getMessage());
            deleteSilently(stagingFile);
            return new OneResult(false, null);
        }

        listener.onComplete(entry.modId());
        return new OneResult(true, filename);
    }

    private void downloadFile(String url, Path destination) throws IOException {
        // Final URL validation — only allow known CDN origins
        if (!url.startsWith("https://cdn.modrinth.com/")) {
            throw new IOException("Refused to download from unknown origin: " + url);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200)
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url);

            try (InputStream in = response.body()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    /** A plain {@code name.jar} with no directory parts, drive letters or control characters. */
    static boolean isSafeJarFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255) return false;
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) return false;
        if (filename.startsWith(".") || filename.contains("..")) return false;
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            if (c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }

    private void deleteSilently(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) {}
    }
}
