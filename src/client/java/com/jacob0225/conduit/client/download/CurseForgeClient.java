package com.jacob0225.conduit.client.download;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Minimal CurseForge Core API v1 client.
 *
 * Used only as a fallback when a mod is not available on Modrinth.
 *
 * Requires a CurseForge API key. Store this in the client config, NOT
 * in the mod JAR or the server manifest.
 *
 * Base URL is a compile-time constant; the server cannot redirect downloads.
 */
public class CurseForgeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/CurseForge");
    private static final String BASE_URL = "https://api.curseforge.com/v1";
    private static final String USER_AGENT = "Conduit/1.0 (github.com/jacob0225/conduit)";

    /** Resolved download info for one CurseForge file. */
    public record VersionInfo(
            String versionString,
            String downloadUrl,   // validated to be mediafilez.forgecdn.net
            String sha1,          // CurseForge only provides SHA-1; we cross-check with server hash
            String filename
    ) {}

    private final HttpClient http;
    private final String apiKey;
    private final String minecraftVersion;

    public CurseForgeClient(String apiKey, String minecraftVersion) {
        this.apiKey = apiKey;
        this.minecraftVersion = minecraftVersion;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Resolves the best matching file for the given CurseForge project ID.
     *
     * @param projectId       validated numeric CurseForge project ID
     * @param requiredVersion version string from the manifest (used for filtering)
     */
    public Optional<VersionInfo> resolve(String projectId, String requiredVersion) {
        String url = BASE_URL + "/mods/" + projectId + "/files"
                + "?gameVersion=" + minecraftVersion
                + "&modLoaderType=4"; // 4 = Fabric

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-api-key", apiKey)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warn("CurseForge returned {} for project {}", response.statusCode(), projectId);
                return Optional.empty();
            }

            return parseFileList(response.body(), requiredVersion);
        } catch (Exception e) {
            LOGGER.warn("CurseForge API error for {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Optional<VersionInfo> parseFileList(String json, String requiredVersion) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray data  = root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) return Optional.empty();

        for (JsonElement el : data) {
            JsonObject file   = el.getAsJsonObject();
            String displayName = file.get("displayName").getAsString();

            // Simple version matching — refine with semver if needed
            if (!displayName.contains(requiredVersion)) continue;

            String rawUrl = file.has("downloadUrl") && !file.get("downloadUrl").isJsonNull()
                    ? file.get("downloadUrl").getAsString()
                    : null;

            if (rawUrl == null) {
                LOGGER.warn("CurseForge file for project has null downloadUrl (distribution disabled)");
                return Optional.empty();
            }

            // Security: validate download URL origin
            if (!rawUrl.startsWith("https://mediafilez.forgecdn.net/") &&
                !rawUrl.startsWith("https://edge.forgecdn.net/")) {
                LOGGER.error("CurseForge returned suspicious download URL: {}", rawUrl);
                return Optional.empty();
            }

            // Extract SHA-1 from hashes array
            String sha1 = null;
            if (file.has("hashes")) {
                for (JsonElement h : file.getAsJsonArray("hashes")) {
                    JsonObject hash = h.getAsJsonObject();
                    if (hash.get("algo").getAsInt() == 1) { // 1 = SHA-1
                        sha1 = hash.get("value").getAsString().toLowerCase();
                        break;
                    }
                }
            }

            String filename = file.get("fileName").getAsString();
            return Optional.of(new VersionInfo(requiredVersion, rawUrl, sha1, filename));
        }

        return Optional.empty();
    }
}
