package com.jacob0225.conduit.manifest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side Modrinth API v2 client.
 *
 * Uses only {@code java.*} — no Gson, no SLF4J — so it compiles cleanly
 * from the main source set regardless of IDE classpath ordering.
 *
 * Resolves a slug/ID into a {@link ResolvedProject} via:
 *   GET /v2/project/{slug}          → project title
 *   GET /v2/project/{slug}/version  → latest fabric+mcVersion file w/ sha512
 *
 * Results are cached per slug for the server session lifetime.
 */
public class ModrinthProjectResolver {

    private static final Logger LOG = Logger.getLogger("Conduit.ModrinthProjectResolver");
    private static final String BASE = "https://api.modrinth.com/v2";
    private static final String UA   = "Conduit/1.0 (github.com/jacob0225/conduit)";

    private final HttpClient http;
    private final String mcVersion;
    private final Map<String, Optional<ResolvedProject>> cache = new ConcurrentHashMap<>();

    /** Fully-resolved mod info ready for {@link ModEntry}. */
    public record ResolvedProject(
            String slug,
            String modId,
            String displayName,
            String versionNumber,
            String modrinthProjectId,
            String sha512,
            String filename
    ) {}

    public ModrinthProjectResolver(String mcVersion) {
        this.mcVersion = mcVersion;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Resolves a Modrinth slug or project ID to its latest matching version.
     *
     * @param slug          Modrinth slug (e.g. "sodium") or 8-char ID
     * @param modIdOverride Fabric mod ID override; if null/blank the slug is used
     * @return resolved project, or empty on API error / no matching version
     */
    public Optional<ResolvedProject> resolve(String slug, String modIdOverride) {
        if (slug == null || slug.isBlank()) return Optional.empty();

        String key = slug + "|" + (modIdOverride != null ? modIdOverride : "");
        if (cache.containsKey(key)) return cache.get(key);

        Optional<String> title     = fetchTitle(slug);
        Optional<VersionResult> vr = title.isPresent() ? fetchLatest(slug) : Optional.empty();

        if (title.isEmpty() || vr.isEmpty()) {
            cache.put(key, Optional.empty());
            return Optional.empty();
        }

        String modId = (modIdOverride != null && !modIdOverride.isBlank()) ? modIdOverride : slug;
        VersionResult v = vr.get();

        String shortHash = v.sha512().length() >= 12 ? v.sha512().substring(0, 12) + "..." : v.sha512();
        LOG.info("Resolved '" + slug + "' → " + title.get() + " v" + v.versionNumber() + " (" + shortHash + ")");

        ResolvedProject result = new ResolvedProject(
                slug, modId, title.get(), v.versionNumber(), v.projectId(), v.sha512(), v.filename());
        cache.put(key, Optional.of(result));
        return Optional.of(result);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Optional<String> fetchTitle(String slug) {
        String body = get(BASE + "/project/" + slug);
        if (body == null) return Optional.empty();
        String title = extractString(body, "title");
        return Optional.of(title != null ? title : slug);
    }

    private Optional<VersionResult> fetchLatest(String slug) {
        String encodedMc = mcVersion.replace("+", "%2B");
        String url = BASE + "/project/" + slug + "/version"
                + "?game_versions=[%22" + encodedMc + "%22]"
                + "&loaders=[%22fabric%22]";

        String body = get(url);
        if (body == null) return Optional.empty();

        // Body is a JSON array — split into individual version objects
        // by finding top-level '{' ... '}' blocks inside the outer '[' ']'
        for (String vObj : splitJsonArray(body)) {
            String projectId     = extractString(vObj, "project_id");
            String versionNumber = extractString(vObj, "version_number");
            if (projectId == null || versionNumber == null) continue;

            // Find the files array inside this version object
            int filesStart = vObj.indexOf("\"files\"");
            if (filesStart < 0) continue;

            // Pick the primary file (or first file)
            String primaryFile = null;
            for (String fObj : splitJsonArray(vObj.substring(filesStart))) {
                String isPrimary = extractString(fObj, "primary");
                if ("true".equals(isPrimary)) { primaryFile = fObj; break; }
                if (primaryFile == null) primaryFile = fObj;
            }
            if (primaryFile == null) continue;

            String rawUrl = extractString(primaryFile, "url");
            if (rawUrl == null || !rawUrl.startsWith("https://cdn.modrinth.com/")) {
                LOG.warning("Skipping '" + slug + "' version — unexpected CDN URL: " + rawUrl);
                continue;
            }

            // Extract sha512 from the hashes sub-object
            int hashStart = primaryFile.indexOf("\"hashes\"");
            if (hashStart < 0) continue;
            String hashSection = primaryFile.substring(hashStart);
            String sha512 = extractString(hashSection, "sha512");
            if (sha512 == null) {
                LOG.warning("No SHA-512 for '" + slug + "' v" + versionNumber + " — skipping");
                continue;
            }

            String filename = extractString(primaryFile, "filename");
            if (filename == null) filename = slug + ".jar";

            return Optional.of(new VersionResult(projectId, versionNumber, sha512.toLowerCase(), filename));
        }

        LOG.warning("No usable Fabric version found on Modrinth for '" + slug + "' (MC " + mcVersion + ")");
        return Optional.empty();
    }

    /** HTTP GET → response body string, or null on error. */
    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warning("Modrinth API returned " + resp.statusCode() + " for " + url);
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            LOG.warning("Modrinth API request failed: " + e.getMessage());
            return null;
        }
    }

    // ── Minimal JSON helpers (no external dependencies) ───────────────────────

    /**
     * Extracts the value of a simple string or boolean field from a JSON object
     * fragment. Handles {@code "key":"value"} and {@code "key":true/false}.
     */
    private static String extractString(String json, String key) {
        // Match "key": "value" (string)
        Pattern strPat = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = strPat.matcher(json);
        if (m.find()) return m.group(1);

        // Match "key": true/false/null (bare value)
        Pattern barePat = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false|null)");
        Matcher m2 = barePat.matcher(json);
        if (m2.find()) return m2.group(1);

        return null;
    }

    /**
     * Very small JSON array splitter: finds top-level {@code { }} objects
     * inside the first {@code [ ]} block in {@code json}.
     */
    private static java.util.List<String> splitJsonArray(String json) {
        java.util.List<String> results = new java.util.ArrayList<>();
        int start = json.indexOf('[');
        if (start < 0) return results;

        int depth = 0;
        int objStart = -1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    results.add(json.substring(objStart, i + 1));
                    objStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            } else if (c == '"') {
                // Skip over string literals so braces inside strings don't confuse us
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++; // skip escaped char
                    i++;
                }
            }
        }
        return results;
    }

    private record VersionResult(String projectId, String versionNumber, String sha512, String filename) {}
}
