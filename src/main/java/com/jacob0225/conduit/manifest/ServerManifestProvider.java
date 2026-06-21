package com.jacob0225.conduit.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jacob0225.conduit.network.ManifestPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loads {@code config/conduit/manifest.json} from disk, validates every entry,
 * and produces a {@link ManifestPayload} ready to be sent over the network.
 *
 * <p><b>Schema 1</b> (legacy): operator fills in all fields manually.
 *
 * <p><b>Schema 2</b> (simple): operator provides only a Modrinth slug +
 * {@code required} flag; the server fetches name, version, and SHA-512 from
 * the Modrinth API at startup.
 */
public class ServerManifestProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Server");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path manifestPath;
    private final String minecraftVersion;
    private ManifestPayload cachedPayload;

    public ServerManifestProvider(Path configDir, String minecraftVersion) {
        this.manifestPath     = configDir.resolve("conduit").resolve("manifest.json");
        this.minecraftVersion = minecraftVersion;
    }

    /** Backward-compatible single-arg constructor; defaults to MC 26.1. */
    public ServerManifestProvider(Path configDir) {
        this(configDir, "26.1");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean load() {
        if (!Files.exists(manifestPath)) {
            LOGGER.info("Conduit manifest not found at {}; generating a template.", manifestPath);
            writeTemplate();
            cachedPayload = emptyPayload();
            return false;
        }

        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonObject  obj  = root.getAsJsonObject();
            int schemaVersion = obj.has("schemaVersion")
                    ? obj.get("schemaVersion").getAsInt()
                    : 1;

            if (schemaVersion == 2) {
                cachedPayload = buildPayloadV2(GSON.fromJson(root, ServerManifestV2.class));
            } else {
                cachedPayload = buildPayloadV1(GSON.fromJson(root, ServerManifest.class));
            }

            LOGGER.info("Conduit manifest loaded (schema {}): {} mod(s) from '{}'",
                    schemaVersion, cachedPayload.mods().size(), cachedPayload.serverName());
            return true;

        } catch (IOException e) {
            LOGGER.error("Failed to read Conduit manifest: {}", e.getMessage());
            cachedPayload = emptyPayload();
            return false;
        } catch (Exception e) {
            LOGGER.error("Conduit manifest validation failed: {}", e.getMessage());
            cachedPayload = emptyPayload();
            return false;
        }
    }

    public ManifestPayload getPayload() {
        return cachedPayload != null ? cachedPayload : emptyPayload();
    }

    // ── Schema 1 ─────────────────────────────────────────────────────────────

    private ManifestPayload buildPayloadV1(ServerManifest raw) {
        List<ModEntry> entries = new ArrayList<>();
        if (raw.mods != null) {
            for (ServerManifest.ModEntryJson j : raw.mods) {
                entries.add(new ModEntry(
                        j.modId,
                        j.displayName,
                        j.requiredVersion,
                        j.required,
                        j.modrinthProjectId   != null ? j.modrinthProjectId   : "",
                        j.curseforgeProjectId != null ? j.curseforgeProjectId : "",
                        j.hashAlgorithm,
                        j.expectedHash
                ));
            }
        }
        if (entries.size() > ManifestPayload.MAX_MODS)
            throw new IllegalArgumentException("Manifest exceeds max mod count: " + entries.size());

        return new ManifestPayload(
                1,
                raw.manifestVersion,
                raw.serverName != null ? raw.serverName : "Unknown Server",
                entries
        );
    }

    // ── Schema 2 ─────────────────────────────────────────────────────────────

    private ManifestPayload buildPayloadV2(ServerManifestV2 raw) {
        ModrinthProjectResolver resolver = new ModrinthProjectResolver(minecraftVersion);
        List<ModEntry> entries = new ArrayList<>();

        if (raw.mods != null) {
            for (ServerManifestV2.SimpleEntry simple : raw.mods) {
                if (simple.slug == null || simple.slug.isBlank()) {
                    LOGGER.warn("Skipping schema-2 entry with null/blank slug");
                    continue;
                }

                Optional<ModrinthProjectResolver.ResolvedProject> resolved =
                        resolver.resolve(simple.slug, simple.modId);

                if (resolved.isEmpty()) {
                    LOGGER.warn("Could not resolve Modrinth slug '{}'; skipping", simple.slug);
                    continue;
                }

                ModrinthProjectResolver.ResolvedProject p = resolved.get();
                try {
                    entries.add(new ModEntry(
                            p.modId(),
                            p.displayName(),
                            p.versionNumber(),
                            simple.required,
                            p.modrinthProjectId(),
                            "",           // CurseForge not used in schema 2
                            "SHA-512",
                            p.sha512()
                    ));
                } catch (SecurityException e) {
                    LOGGER.warn("Validation failed for '{}': {}; skipping", simple.slug, e.getMessage());
                }
            }
        }

        if (entries.size() > ManifestPayload.MAX_MODS)
            throw new IllegalArgumentException("Manifest exceeds max mod count: " + entries.size());

        return new ManifestPayload(
                ManifestPayload.CURRENT_SCHEMA,
                1,
                raw.serverName != null ? raw.serverName : "Unknown Server",
                entries
        );
    }

    // ── Template ─────────────────────────────────────────────────────────────

    private void writeTemplate() {
        try {
            Path parent = manifestPath.getParent();
            if (parent != null) Files.createDirectories(parent);

            String template = """
                    {
                      "_comment": "Conduit manifest (schema 2). Add one entry per mod. Just the Modrinth slug + required flag — the server resolves the version and hash automatically at startup.",
                      "schemaVersion": 2,
                      "serverName": "My Conduit Server",
                      "mods": [
                        {
                          "slug": "sodium",
                          "required": true
                        }
                      ]
                    }
                    """;

            try (Writer writer = Files.newBufferedWriter(manifestPath)) {
                writer.write(template);
            }
            LOGGER.info("Wrote Conduit manifest template to {}. Edit it and restart to sync mods.", manifestPath);
        } catch (IOException e) {
            LOGGER.error("Could not write Conduit manifest template: {}", e.getMessage());
        }
    }

    private ManifestPayload emptyPayload() {
        return new ManifestPayload(ManifestPayload.CURRENT_SCHEMA, 0, "Unknown Server", List.of());
    }
}
