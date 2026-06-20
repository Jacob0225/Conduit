package com.jacob0225.conduit.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jacob0225.conduit.network.ManifestPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code config/conduit/manifest.json} from disk, validates every entry
 * through {@link ModEntry}'s constructor (which calls {@link com.jacob0225.conduit.security.InputValidator}),
 * and produces a {@link ManifestPayload} ready to be sent over the network.
 *
 * The manifest is reloaded on every server startup. Hot-reload can be added
 * later via a server command (e.g., `/conduit reload`).
 */
public class ServerManifestProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Server");
    private static final Gson GSON = new GsonBuilder().create();

    private final Path manifestPath;
    private ManifestPayload cachedPayload;

    public ServerManifestProvider(Path configDir) {
        this.manifestPath = configDir.resolve("conduit").resolve("manifest.json");
    }

    /**
     * Loads and validates the manifest from disk.
     * Returns {@code true} if the manifest was loaded successfully.
     */
    public boolean load() {
        if (!Files.exists(manifestPath)) {
            LOGGER.warn("Conduit manifest not found at {}; no mods will be synced.", manifestPath);
            cachedPayload = emptyPayload();
            return false;
        }

        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            ServerManifest raw = GSON.fromJson(reader, ServerManifest.class);
            cachedPayload = buildPayload(raw);
            LOGGER.info("Conduit manifest loaded: {} mod(s) from '{}'",
                    cachedPayload.mods().size(), cachedPayload.serverName());
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

    /** Returns the cached payload; call {@link #load()} first. */
    public ManifestPayload getPayload() {
        return cachedPayload != null ? cachedPayload : emptyPayload();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ManifestPayload buildPayload(ServerManifest raw) {
        if (raw.schemaVersion != ManifestPayload.CURRENT_SCHEMA)
            throw new IllegalArgumentException(
                    "Unsupported manifest schemaVersion: " + raw.schemaVersion);

        List<ModEntry> entries = new ArrayList<>();
        if (raw.mods != null) {
            for (ServerManifest.ModEntryJson j : raw.mods) {
                // ModEntry constructor validates every field — throws on bad data
                entries.add(new ModEntry(
                        j.modId,
                        j.displayName,
                        j.requiredVersion,
                        j.required,
                        j.modrinthProjectId != null ? j.modrinthProjectId : "",
                        j.curseforgeProjectId != null ? j.curseforgeProjectId : "",
                        j.hashAlgorithm,
                        j.expectedHash
                ));
            }
        }

        if (entries.size() > ManifestPayload.MAX_MODS)
            throw new IllegalArgumentException("Manifest exceeds max mod count: " + entries.size());

        return new ManifestPayload(
                raw.schemaVersion,
                raw.manifestVersion,
                raw.serverName != null ? raw.serverName : "Unknown Server",
                entries
        );
    }

    private ManifestPayload emptyPayload() {
        return new ManifestPayload(ManifestPayload.CURRENT_SCHEMA, 0, "Unknown Server", List.of());
    }
}
