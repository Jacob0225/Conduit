package com.jacob0225.conduit.client.download;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Snapshot of currently installed mods, built from BOTH the Fabric loader's
 * loaded mod list AND a direct scan of the {@code mods/} directory.
 *
 * <p>The loaded-mod list alone is unreliable here: it only reflects mods loaded
 * <i>this session</i>, so a jar that was just dropped into {@code mods/} (by
 * Conduit's own installer) or that failed to load wouldn't be counted, and the
 * verifier would keep flagging it as missing. Scanning the jars directly also
 * lets us match by Modrinth project id — useful because a manifest's
 * {@code modId} is the Fabric id but the server may identify mods by their
 * Modrinth slug/ID, and the two don't always agree.
 *
 * <p>Each jar's {@code fabric.mod.json} is parsed minimally (no Gson needed) to
 * extract: {@code id}, {@code version}, and the Modrinth project id from the
 * {@code custom.modrinth} field (where the Fabric loader spec puts it).
 */
public class InstalledModIndex {

    /** modId (Fabric id OR Modrinth project id) → installed version string */
    private final Map<String, String> installedVersions;

    public InstalledModIndex() {
        installedVersions = new HashMap<>();

        // 1. Loaded mods — authoritative for mods active this session.
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String id      = container.getMetadata().getId();
            String version = container.getMetadata().getVersion().getFriendlyString();
            installedVersions.put(id, version);
        }

        // 2. Jars in mods/ — catches jars not yet loaded (e.g. just installed by
        //    Conduit this run, or failed to load) and lets us pick up Modrinth IDs.
        scanModsDirectory();
    }

    /** Returns true if the given modId is installed (any version). */
    public boolean isInstalled(String modId) {
        return installedVersions.containsKey(modId);
    }

    /** Returns the installed version for the given modId, or empty if not installed. */
    public Optional<String> getInstalledVersion(String modId) {
        return Optional.ofNullable(installedVersions.get(modId));
    }

    /**
     * Returns true if the installed version satisfies the required version.
     *
     * Checks exact equality first, then falls back to checking whether either
     * version string contains the other — this handles common Fabric version
     * string variations like "0.6.0+mc26.1" vs "0.6.0+mc26.1+build.100".
     *
     * Also normalises by stripping build metadata after '+' for comparison,
     * so "1.2.3+mc26.1+build.99" is considered equal to "1.2.3+mc26.1".
     */
    public boolean hasSatisfyingVersion(String modId, String requiredVersion) {
        String installed = installedVersions.get(modId);
        if (installed == null) return false;

        // 1. Exact match
        if (installed.equals(requiredVersion)) return true;

        // 2. Strip build metadata (everything after the second '+') and compare
        String normInstalled = stripBuildMeta(installed);
        String normRequired  = stripBuildMeta(requiredVersion);
        if (normInstalled.equals(normRequired)) return true;

        // 3. One contains the other (handles reversed metadata ordering)
        if (installed.contains(requiredVersion) || requiredVersion.contains(installed)) return true;
        if (normInstalled.contains(normRequired) || normRequired.contains(normInstalled)) return true;

        return false;
    }

    /** Strips everything after the second '+' sign (build metadata). */
    private static String stripBuildMeta(String version) {
        int first = version.indexOf('+');
        if (first < 0) return version;
        int second = version.indexOf('+', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    // ── Mods directory scan ──────────────────────────────────────────────────

    private void scanModsDirectory() {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return;

        try (var stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : stream) {
                readFabricModJson(jar).ifPresent(fmj -> {
                    String id      = fmj.get("id");
                    String version = fmj.get("version");
                    String modrinth = fmj.get("modrinth");
                    if (id != null && version != null) {
                        installedVersions.putIfAbsent(id, version);
                    }
                    // Index by Modrinth project id too, so a manifest entry that
                    // only carries the Modrinth ID still matches this jar.
                    if (modrinth != null && !modrinth.isEmpty() && version != null) {
                        installedVersions.putIfAbsent(modrinth, version);
                    }
                });
            }
        } catch (IOException e) {
            // Non-fatal: the loaded-mod list from the constructor still stands.
        }
    }

    /**
     * Reads {@code fabric.mod.json} from a jar and extracts the three fields we
     * care about. Returns empty if the jar isn't a Fabric mod or can't be read.
     */
    private Optional<FabricModJson> readFabricModJson(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("fabric.mod.json");
            if (entry == null) return Optional.empty();

            String json;
            try (InputStream in = jar.getInputStream(entry)) {
                json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            String id       = extractStringField(json, "id");
            String version  = extractStringField(json, "version");
            String modrinth = extractModrinthId(json);
            if (id == null || version == null) return Optional.empty();

            return Optional.of(new FabricModJson(id, version, modrinth));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Pulls the Modrinth project id out of the {@code custom.modrinth} field of
     * fabric.mod.json. The Fabric loader spec reserves {@code custom.modrinth}
     * for this, so this is where Modrinth-published mods store their ID.
     */
    private static String extractModrinthId(String json) {
        int customIdx = json.indexOf("\"custom\"");
        if (customIdx < 0) return null;
        String tail = json.substring(customIdx);
        int modrinthIdx = tail.indexOf("\"modrinth\"");
        if (modrinthIdx < 0) return null;
        // Look for the value after "modrinth" within the custom block.
        String after = tail.substring(modrinthIdx);
        return extractStringField(after, "modrinth");
    }

    /**
     * Minimal JSON string-field extractor: matches {@code "key" : "value"}.
     * Good enough for fabric.mod.json — values we read are simple strings with
     * no embedded escapes beyond what this handles.
     */
    private static String extractStringField(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int start = -1;
        // Find the opening quote of the value (skip whitespace).
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') { start = i + 1; break; }
            if (!Character.isWhitespace(c)) return null; // value isn't a string
        }
        if (start < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { // escape
                out.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    /** Parsed fields from a single fabric.mod.json. */
    private record FabricModJson(String id, String version, String modrinth) {
        String get(String field) {
            return switch (field) {
                case "id" -> id;
                case "version" -> version;
                case "modrinth" -> modrinth;
                default -> null;
            };
        }
    }
}
