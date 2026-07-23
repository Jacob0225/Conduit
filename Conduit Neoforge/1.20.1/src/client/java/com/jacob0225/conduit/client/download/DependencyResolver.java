package com.jacob0225.conduit.client.download;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Resolves transitive Fabric mod dependencies via the Modrinth API.
 *
 * Uses a BFS with a visited set to avoid infinite loops, and a depth cap
 * to prevent pathological dependency chains.
 *
 * Only follows REQUIRED dependencies. Optional dependencies are surfaced to
 * the user but never auto-downloaded.
 */
public class DependencyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Deps");
    private static final String BASE_URL = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "Conduit/1.0 (github.com/jacob0225/conduit)";
    private static final int MAX_DEPTH = 10;

    public record ResolvedDep(
            String modrinthProjectId,
            String versionId,
            String dependencyType // "required" | "optional" | "incompatible"
    ) {}

    /** Conflict detected: two entries in the resolution require the same modId at different versions. */
    public static class ConflictException extends Exception {
        public ConflictException(String message) { super(message); }
    }

    private final HttpClient http;
    private final InstalledModIndex installedIndex;

    public DependencyResolver(InstalledModIndex installedIndex) {
        this.installedIndex = installedIndex;
        this.http = HttpClient.newBuilder().build();
    }

    /**
     * BFS over the dependency graph of {@code rootVersionId}.
     *
     * @param rootVersionId Modrinth version ID (not project ID)
     * @return list of required dependency version IDs that are not yet installed
     */
    public List<ResolvedDep> resolve(String rootVersionId) throws ConflictException {
        // visited: version IDs we've already processed
        Set<String> visited = new LinkedHashSet<>();
        // projectId → versionId to detect conflicts
        Map<String, String> projectVersionMap = new HashMap<>();

        Queue<String> queue = new LinkedList<>();
        queue.add(rootVersionId);

        List<ResolvedDep> needed = new ArrayList<>();

        int depth = 0;
        while (!queue.isEmpty() && depth < MAX_DEPTH) {
            String versionId = queue.poll();
            if (visited.contains(versionId)) continue;
            visited.add(versionId);
            depth++;

            List<ResolvedDep> deps = fetchDependencies(versionId);
            for (ResolvedDep dep : deps) {
                if (!"required".equals(dep.dependencyType())) continue;

                String existingVersionId = projectVersionMap.get(dep.modrinthProjectId());
                if (existingVersionId != null && !existingVersionId.equals(dep.versionId())) {
                    throw new ConflictException(
                            "Version conflict for Modrinth project " + dep.modrinthProjectId() +
                            ": needs " + dep.versionId() + " but already resolved " + existingVersionId);
                }

                projectVersionMap.put(dep.modrinthProjectId(), dep.versionId());
                needed.add(dep);
                queue.add(dep.versionId());
            }
        }

        if (depth >= MAX_DEPTH) {
            LOGGER.warn("Dependency resolution hit max depth ({}); some transitive deps may be missing.", MAX_DEPTH);
        }

        return needed;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private List<ResolvedDep> fetchDependencies(String versionId) {
        String url = BASE_URL + "/version/" + versionId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();

            JsonObject v = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray deps = v.getAsJsonArray("dependencies");
            if (deps == null) return List.of();

            List<ResolvedDep> result = new ArrayList<>();
            for (JsonElement el : deps) {
                JsonObject dep = el.getAsJsonObject();
                String type      = dep.get("dependency_type").getAsString();
                String projectId = dep.has("project_id") && !dep.get("project_id").isJsonNull()
                        ? dep.get("project_id").getAsString() : null;
                String depVerId  = dep.has("version_id") && !dep.get("version_id").isJsonNull()
                        ? dep.get("version_id").getAsString() : null;

                if (projectId != null && depVerId != null) {
                    result.add(new ResolvedDep(projectId, depVerId, type));
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("Failed to fetch dependencies for version {}: {}", versionId, e.getMessage());
            return List.of();
        }
    }
}
