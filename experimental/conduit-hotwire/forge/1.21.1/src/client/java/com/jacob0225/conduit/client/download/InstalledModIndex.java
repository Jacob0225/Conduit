package com.jacob0225.conduit.client.download;

import com.jacob0225.conduit.client.PlatformPaths;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Snapshot of currently installed mods, built from BOTH Forge's
 * loaded mod list AND a direct scan of the {@code mods/} directory.
 *
 * <p>The loaded-mod list alone is unreliable here: it only reflects mods loaded
 * <i>this session</i>, so a jar that was just dropped into {@code mods/} (by
 * Conduit's own installer) or that failed to load wouldn't be counted, and the
 * verifier would keep flagging it as missing. Scanning the jars directly also
 * lets us match by Modrinth project id — useful because a manifest's
 * {@code modId} is the Forge id but the server may identify mods by their
 * Modrinth slug/ID, and the two don't always agree.
 *
 * <p>Each jar's {@code META-INF/mods.toml} is parsed minimally so a
 * newly installed jar is visible before the next game restart.
 */
public class InstalledModIndex {

    /** Forge mod id → installed version string. */
    private final Map<String, String> installedVersions;

    public InstalledModIndex() {
        installedVersions = new HashMap<>();

        // 1. Loaded mods — authoritative for mods active this session.
        for (var container : ModList.get().getMods()) {
            String id      = container.getModId();
            String version = container.getVersion().toString();
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
        Path modsDir = PlatformPaths.gameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return;

        try (var stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : stream) {
                readForgeModToml(jar).ifPresent(mod ->
                        installedVersions.putIfAbsent(mod.id(), mod.version()));
            }
        } catch (IOException e) {
            // Non-fatal: the loaded-mod list from the constructor still stands.
        }
    }

    /**
     * Reads Forge TOML metadata from a jar. Forge metadata is accepted too
     * because many 1.21.1 jars retain the older filename for compatibility.
     */
    private Optional<ForgeMod> readForgeModToml(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("META-INF/mods.toml");
            if (entry == null) return Optional.empty();
            String toml;
            try (InputStream in = jar.getInputStream(entry)) {
                toml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            Matcher id = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([a-z0-9_-]+)[\"']").matcher(toml);
            Matcher version = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']").matcher(toml);
            if (!id.find() || !version.find() || version.group(1).contains("${")) return Optional.empty();
            return Optional.of(new ForgeMod(id.group(1), version.group(1)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
    private record ForgeMod(String id, String version) {}
}
