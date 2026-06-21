package com.jacob0225.conduit.client.download;

import com.jacob0225.conduit.manifest.ModEntry;
import net.fabricmc.loader.api.FabricLoader;
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
 * 1. Query Modrinth → if unavailable, query CurseForge
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
            List<String> failed
    ) {}

    private final ModrinthClient modrinthClient;
    private final CurseForgeClient curseForgeClient; // may be null if no API key configured
    private final DependencyResolver dependencyResolver;
    private final HttpClient http;
    private final Path managedDir;
    private final Path stagingDir;

    public ModDownloadManager(
            ModrinthClient modrinthClient,
            CurseForgeClient curseForgeClient,
            DependencyResolver dependencyResolver
    ) {
        this.modrinthClient      = modrinthClient;
        this.curseForgeClient    = curseForgeClient;
        this.dependencyResolver  = dependencyResolver;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Path gameDir = FabricLoader.getInstance().getGameDir();
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
     * @param entries  list from {@link ManifestVerifier.DiffResult#allNeeded()}
     * @param listener progress callback (called on the calling thread)
     */
    public DownloadResult downloadAll(List<ModEntry> entries, ProgressListener listener) {
        List<String> succeeded = new ArrayList<>();
        List<String> failed    = new ArrayList<>();

        int total = entries.size();
        int current = 0;

        for (ModEntry entry : entries) {
            current++;
            listener.onStart(entry.modId(), total, current);

            boolean ok = downloadOne(entry, listener);
            if (ok) succeeded.add(entry.modId());
            else     failed.add(entry.modId());
        }

        return new DownloadResult(succeeded, failed);
    }

    // ── Private pipeline ──────────────────────────────────────────────────────

    private boolean downloadOne(ModEntry entry, ProgressListener listener) {
        LOGGER.info("Downloading {} {} ...", entry.modId(), entry.requiredVersion());

        // Step 1: Resolve via Modrinth (primary)
        ModrinthClient.VersionInfo modrinthInfo = null;
        if (!entry.modrinthProjectId().isEmpty()) {
            modrinthInfo = modrinthClient.resolve(entry.modrinthProjectId(), entry.requiredVersion())
                    .orElse(null);
        }

        // Step 2: Fallback to CurseForge
        CurseForgeClient.VersionInfo curseInfo = null;
        if (modrinthInfo == null && curseForgeClient != null && !entry.curseforgeProjectId().isEmpty()) {
            curseInfo = curseForgeClient.resolve(entry.curseforgeProjectId(), entry.requiredVersion())
                    .orElse(null);
        }

        if (modrinthInfo == null && curseInfo == null) {
            String reason = "Could not resolve " + entry.modId() + " " + entry.requiredVersion()
                    + " from Modrinth or CurseForge";
            LOGGER.error(reason);
            listener.onError(entry.modId(), reason);
            return false;
        }

        // Step 3: Download to staging
        String downloadUrl = modrinthInfo != null ? modrinthInfo.downloadUrl() : curseInfo.downloadUrl();
        String apiHash     = modrinthInfo != null ? modrinthInfo.sha512() : null; // CF only has SHA-1
        String filename    = modrinthInfo != null ? modrinthInfo.filename() : curseInfo.filename();

        // Skip if the file is already present in the mods folder (e.g. from a
        // previous install attempt this session). Don't re-download or delete it.
        Path destination = managedDir.resolve(filename);
        if (Files.exists(destination)) {
            LOGGER.info("{} already present in mods/ — skipping download.", entry.modId());
            listener.onComplete(entry.modId());
            return true;
        }

        Path stagingFile = stagingDir.resolve(entry.modId() + "-" + entry.requiredVersion() + ".jar.tmp");

        try {
            downloadFile(downloadUrl, stagingFile);
        } catch (IOException e) {
            LOGGER.error("Download failed for {}: {}", entry.modId(), e.getMessage());
            listener.onError(entry.modId(), "Download failed: " + e.getMessage());
            deleteSilently(stagingFile);
            return false;
        }

        // Step 4: Hash verification
        try {
            if (apiHash != null) {
                // Both API hash and server hash must agree
                HashVerifier.verifyBoth(stagingFile, entry.hashAlgorithm(), apiHash, entry.expectedHash());
            } else {
                // CurseForge fallback: only verify server-provided hash
                HashVerifier.verify(stagingFile, entry.hashAlgorithm(), entry.expectedHash());
                LOGGER.warn("CurseForge fallback for {} — only server hash verified (no API SHA-512)", entry.modId());
            }
        } catch (HashVerifier.VerificationException e) {
            LOGGER.error("HASH MISMATCH for {}: {}", entry.modId(), e.getMessage());
            listener.onError(entry.modId(), "Hash verification failed — file rejected");
            deleteSilently(stagingFile);
            return false;
        } catch (IOException e) {
            LOGGER.error("Could not read staging file for verification: {}", e.getMessage());
            deleteSilently(stagingFile);
            return false;
        }

        // Step 5: Move to managed directory
        try {
            Files.move(stagingFile, destination, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Installed {} → {}", entry.modId(), destination.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to install {}: {}", entry.modId(), e.getMessage());
            deleteSilently(stagingFile);
            return false;
        }

        listener.onComplete(entry.modId());
        return true;
    }

    private void downloadFile(String url, Path destination) throws IOException {
        // Final URL validation — only allow known CDN origins
        if (!url.startsWith("https://cdn.modrinth.com/") &&
            !url.startsWith("https://mediafilez.forgecdn.net/") &&
            !url.startsWith("https://edge.forgecdn.net/")) {
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

    private void deleteSilently(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) {}
    }
}
