package com.jacob0225.conduit.client.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import com.jacob0225.conduit.client.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent, per-server record of the mods Conduit has installed for that
 * server.
 *
 * <p>This is the pass-through gate: when the player joins a server, we load the
 * record for that host+port and check whether it satisfies the server's current
 * manifest. If it does, the player goes straight in (no prompt). If not — first
 * join, a mod was added/updated, or the record is missing — the install screen
 * appears, and on a successful download the record is (re)written.
 *
 * <p>Storing the record per server (rather than scanning the global mods folder)
 * means a player can keep separate mod sets for different servers without one
 * server's install masking another's missing mods.
 *
 * <p>File layout: {@code <gameDir>/config/conduit/servers/<host>_<port>.json}.
 * The host is sanitised to a filename-safe form so IPv6 literals and odd
 * hostnames can't escape the servers directory.
 *
 * <p>JSON shape:
 * <pre>{@code
 * {
 *   "serverName": "Jacob's Silly Server",
 *   "installedAt": 1689600000000,
 *   "mods": [
 *     { "modId": "sodium", "version": "0.6.0+mc26.1",
 *       "modrinthProjectId": "AANobbMI",
 *       "hashAlgorithm": "SHA-512", "expectedHash": "..." }
 *   ]
 * }
 * }</pre>
 */
public class ServerInstallRecord {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Record");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String RECORDS_DIR = "config/conduit/servers";

    private final Path file;

    /** Mods recorded as installed for this server. */
    private List<RecordedMod> mods = new ArrayList<>();
    /** Every jar Conduit has installed for this server, including superseded versions. */
    private final Set<String> managedJarFilenames = new HashSet<>();
    private String serverName = "";

    /** One mod entry persisted in the record. */
    public record RecordedMod(
            String modId,
            String version,
            String modrinthProjectId,
            String hashAlgorithm,
            String expectedHash,
            String jarFilename // the jar's filename inside mods/ (for stash management)
    ) {}

    /** Opaque key identifying a server, used by {@link ModStashManager}. */
    public record ServerKey(String fileName) {

        /** Build a key for the given host/port (same scheme as record filenames). */
        public static ServerKey forServer(String host, int gamePort) {
            return new ServerKey(safeFileName(host, gamePort));
        }

        /** The stem of the record file, e.g. {@code mc_example_com_25565}. */
        public String stem() {
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
    }

    private ServerInstallRecord(Path file) {
        this.file = file;
    }

    // ── Loading / saving ──────────────────────────────────────────────────────

    /** Load the record for the given server, or return an empty record if none exists yet. */
    public static ServerInstallRecord forServer(String host, int gamePort) {
        Path file = recordsDir().resolve(safeFileName(host, gamePort));
        ServerInstallRecord rec = new ServerInstallRecord(file);
        rec.load();
        return rec;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            serverName = root.has("serverName") ? root.get("serverName").getAsString() : "";
            mods.clear();
            managedJarFilenames.clear();
            if (root.has("managedJarFilenames") && root.get("managedJarFilenames").isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray("managedJarFilenames")) {
                    if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                        managedJarFilenames.add(e.getAsString());
                    }
                }
            }
            if (root.has("mods") && root.get("mods").isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray("mods")) {
                    JsonObject m = e.getAsJsonObject();
                    mods.add(new RecordedMod(
                            getStr(m, "modId"),
                            getStr(m, "version"),
                            getStr(m, "modrinthProjectId"),
                            getStr(m, "hashAlgorithm"),
                            getStr(m, "expectedHash"),
                            getStr(m, "jarFilename")
                    ));
                }
            }
            for (RecordedMod mod : mods) {
                if (mod.jarFilename() != null && !mod.jarFilename().isBlank()) {
                    managedJarFilenames.add(mod.jarFilename());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read install record {}: {}", file, e.getMessage());
        }
    }

    /**
     * Persist the record with the mods Conduit just installed for this server.
     * Replaces any prior content atomically enough for this purpose.
     *
     * @param jarFilenames maps modId → the jar filename actually placed in
     *                     {@code mods/}; recorded so the stash manager knows
     *                     which files Conduit owns for this server
     */
    public void save(String serverName, List<ModEntry> installedMods,
                     java.util.Map<String, String> jarFilenames) {
        this.serverName = serverName != null ? serverName : "";
        this.mods = new ArrayList<>();
        for (ModEntry m : installedMods) {
            this.mods.add(new RecordedMod(
                    m.modId(), m.requiredVersion(), m.modrinthProjectId(),
                    m.hashAlgorithm(), m.expectedHash(),
                    jarFilenames.getOrDefault(m.modId(), "")));
        }
        for (String filename : jarFilenames.values()) {
            if (filename != null && !filename.isBlank()) managedJarFilenames.add(filename);
        }

        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("serverName", this.serverName);
            root.addProperty("installedAt", System.currentTimeMillis());
            JsonArray managed = new JsonArray();
            managedJarFilenames.stream().sorted().forEach(managed::add);
            root.add("managedJarFilenames", managed);
            JsonArray arr = new JsonArray();
            for (RecordedMod m : this.mods) {
                JsonObject o = new JsonObject();
                o.addProperty("modId", m.modId());
                o.addProperty("version", m.version());
                o.addProperty("modrinthProjectId", m.modrinthProjectId());
                o.addProperty("hashAlgorithm", m.hashAlgorithm());
                o.addProperty("expectedHash", m.expectedHash());
                o.addProperty("jarFilename", m.jarFilename());
                arr.add(o);
            }
            root.add("mods", arr);

            try (Writer w = Files.newBufferedWriter(file)) {
                w.write(GSON.toJson(root));
            }
            LOGGER.info("Wrote install record for '{}' ({} mods) → {}", this.serverName, this.mods.size(), file);
        } catch (IOException e) {
            LOGGER.error("Could not write install record {}: {}", file, e.getMessage());
        }
    }

    // ── The gate ──────────────────────────────────────────────────────────────

    /**
     * Returns true if this record satisfies the given manifest — i.e. every
     * <b>required</b> mod in the manifest is present in the record with a
     * matching version. Optional mods never gate the join.
     *
     * <p>Matching is by Fabric modId first, then by Modrinth project id, so a
     * record matches regardless of which identifier the manifest leads with.
     */
    public boolean isSatisfied(ManifestPayload payload) {
        for (ModEntry required : payload.mods()) {
            if (!required.required()) continue;

            Optional<RecordedMod> match = findMatch(required);
            if (match.isEmpty()) {
                LOGGER.info("Record missing required mod '{}' — not satisfied", required.modId());
                return false;
            }
            if (!versionSatisfies(match.get().version(), required.requiredVersion())) {
                LOGGER.info("Record has '{}' v{} but server requires v{} — not satisfied",
                        required.modId(), match.get().version(), required.requiredVersion());
                return false;
            }
        }
        return true;
    }

    private Optional<RecordedMod> findMatch(ModEntry entry) {
        for (RecordedMod m : mods) {
            if (m.modId().equals(entry.modId())) return Optional.of(m);
        }
        // Fall back to Modrinth project id for entries the server identifies that way.
        if (!entry.modrinthProjectId().isEmpty()) {
            for (RecordedMod m : mods) {
                if (entry.modrinthProjectId().equals(m.modrinthProjectId())) return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /**
     * Same version-satisfaction logic as {@link InstalledModIndex}: exact match,
     * then build-metadata-stripped equality, then substring containment. Handles
     * Fabric version-string variants like "0.6.0+mc26.1" vs "0.6.0+mc26.1+b.100".
     */
    private static boolean versionSatisfies(String installed, String required) {
        if (installed.equals(required)) return true;
        String ni = stripBuildMeta(installed);
        String nr = stripBuildMeta(required);
        if (ni.equals(nr)) return true;
        return installed.contains(required) || required.contains(installed)
                || ni.contains(nr) || nr.contains(ni);
    }

    private static String stripBuildMeta(String version) {
        int first = version.indexOf('+');
        if (first < 0) return version;
        int second = version.indexOf('+', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    // ── Stash / sanity-check support ─────────────────────────────────────────

    /** The set of jar filenames Conduit owns for this server. */
    public Set<String> ownedJarFilenames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (RecordedMod m : mods) {
            if (m.jarFilename() != null && !m.jarFilename().isEmpty()) {
                names.add(m.jarFilename());
            }
        }
        return names;
    }

    /**
     * Every jar Conduit has ever placed in this server's mod set. Superseded
     * jars are retained here so they can still be stashed during a server swap.
     */
    public Set<String> managedJarFilenames() {
        return Set.copyOf(managedJarFilenames);
    }

    /** The opaque key for this server (used to namespace the stash dir). */
    public ServerKey key() {
        return new ServerKey(file.getFileName().toString());
    }

    /** The record file on disk (so the sanity check can delete stale records). */
    public Path file() {
        return file;
    }

    /** The configured server name from the record, or "" if unset. */
    public String serverName() {
        return serverName;
    }

    /**
     * Load every record file in the servers directory. Used by the stash manager
     * (to find other servers' owned jars) and the startup sanity check.
     */
    public static List<ServerInstallRecord> allRecords() {
        List<ServerInstallRecord> out = new ArrayList<>();
        Path dir = recordsDir();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                ServerInstallRecord r = new ServerInstallRecord(p);
                r.load();
                out.add(r);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list install records in {}: {}", dir, e.getMessage());
        }
        return out;
    }

    /** Delete this record file from disk (used when a sanity check invalidates it). */
    public void delete() {
        try {
            Files.deleteIfExists(file);
            LOGGER.info("Deleted stale install record {}", file);
        } catch (IOException e) {
            LOGGER.warn("Could not delete install record {}: {}", file, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Path recordsDir() {
        return PlatformPaths.gameDir().resolve(RECORDS_DIR);
    }

    private static String getStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /**
     * Build a filename for the record that stays inside the servers directory.
     * Anything that isn't [a-z0-9] becomes an underscore; host and port are
     * joined with one underscore. This keeps IPv6 literals ({@code ::1}) and
     * unusual hostnames from producing path-traversal or bad filenames.
     */
    private static String safeFileName(String host, int port) {
        String safeHost = host.toLowerCase().replaceAll("[^a-z0-9]", "_");
        return safeHost + "_" + port + ".json";
    }
}
