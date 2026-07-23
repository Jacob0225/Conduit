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
 * Minimal Modrinth API v2 client.
 *
 * Only calls:
 *   GET /v2/project/{id}/version  (list versions)
 *
 * The base URL is a compile-time constant — the server cannot influence
 * which endpoint is queried.
 *
 * HTTPS is enforced; plaintext HTTP is never used.
 */
public class ModrinthClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Modrinth");
    private static final String BASE_URL = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "Conduit/1.0 (github.com/jacob0225/conduit)";

    private final HttpClient http;
    private final String minecraftVersion;

    /** Resolved download info for one mod version. */
    public record VersionInfo(
            String versionNumber,
            String downloadUrl,   // HTTPS only, verified to be api.modrinth.com
            String sha512,        // lowercase hex
            String filename
    ) {}

    public ModrinthClient(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Resolves the best matching version for the given Modrinth project ID and
     * required version string.
     *
     * @param projectId      validated 8-char Modrinth project ID
     * @param requiredVersion exact version string from the manifest
     * @return resolved download info, or empty if the version is not found
     */
    public Optional<VersionInfo> resolve(String projectId, String requiredVersion) {
        // Build query: filter by game version and loader to narrow results
        String url = BASE_URL + "/project/" + projectId + "/version"
                + "?game_versions=[%22" + minecraftVersion + "%22]"
                + "&loaders=[%22fabric%22]";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warn("Modrinth returned {} for project {}", response.statusCode(), projectId);
                return Optional.empty();
            }

            return parseVersionList(response.body(), requiredVersion);
        } catch (Exception e) {
            LOGGER.warn("Modrinth API error for {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Optional<VersionInfo> parseVersionList(String json, String requiredVersion) {
        JsonArray versions = JsonParser.parseString(json).getAsJsonArray();

        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            String versionNumber = v.get("version_number").getAsString();

            if (!versionNumber.equals(requiredVersion)) continue;

            JsonArray files = v.getAsJsonArray("files");
            if (files == null || files.isEmpty()) continue;

            // Use the primary file (first in the list)
            JsonObject file = files.get(0).getAsJsonObject();
            String rawUrl   = file.get("url").getAsString();
            String filename = file.get("filename").getAsString();

            // Security: validate the download URL is from cdn.modrinth.com
            if (!rawUrl.startsWith("https://cdn.modrinth.com/")) {
                LOGGER.error("Modrinth returned suspicious download URL for {}: {}",
                        requiredVersion, rawUrl);
                return Optional.empty();
            }

            JsonObject hashes = file.getAsJsonObject("hashes");
            String sha512 = hashes != null && hashes.has("sha512")
                    ? hashes.get("sha512").getAsString().toLowerCase()
                    : null;

            if (sha512 == null) {
                LOGGER.warn("Modrinth file for {} has no SHA-512 hash", requiredVersion);
                return Optional.empty();
            }

            return Optional.of(new VersionInfo(versionNumber, rawUrl, sha512, filename));
        }

        return Optional.empty();
    }
}
