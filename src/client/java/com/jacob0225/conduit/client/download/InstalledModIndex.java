package com.jacob0225.conduit.client.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Snapshot of currently installed mods, built from two sources:
 *
 * <ol>
 *   <li><b>Fabric loader</b> — mods loaded into the JVM at startup.</li>
 *   <li><b>Disk scan</b> of {@code mods/} — JAR files present on disk but not
 *       yet loaded (e.g. downloaded by Conduit this session). Each JAR is
 *       opened as a ZIP and its {@code fabric.mod.json} is read for the mod ID
 *       and version.</li>
 * </ol>
 *
 * Combining both sources means a mod is considered installed as soon as its
 * JAR lands in {@code mods/}, even before the game restarts to actually load
 * it. This prevents Conduit from re-downloading a mod it just installed.
 */
public class InstalledModIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/ModIndex");

    /** modId → installed version string */
    private final Map<String, String> installedVersions;

    public InstalledModIndex() {
        installedVersions = new HashMap<>();

        // Source 1: Fabric loader (mods active in this JVM session)
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String id      = container.getMetadata().getId();
            String version = container.getMetadata().getVersion().getFriendlyString();
            installedVersions.put(id, version);
        }

        // Source 2: Disk scan (JARs on disk not yet loaded — e.g. just downloaded)
        scanModsDirectory();
    }

    // ── Disk scan ─────────────────────────────────────────────────────────────

    private void scanModsDirectory() {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return;

        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .forEach(this::tryRegisterFromJar);
        } catch (IOException e) {
            LOGGER.warn("Conduit: could not list mods/ for disk scan: {}", e.getMessage());
        }
    }

    /**
     * Opens a JAR as a ZIP, reads {@code fabric.mod.json}, and registers the
     * mod ID + version — but only if the Fabric loader hasn't already done so
     * (loader data is authoritative for the currently-running version).
     */
    private void tryRegisterFromJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry fmj = zip.getEntry("fabric.mod.json");
            if (fmj == null) return;

            String json;
            try (InputStream in = zip.getInputStream(fmj)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("id") || !obj.has("version")) return;

            String id      = obj.get("id").getAsString();
            String version = obj.get("version").getAsString();
            if (id.isBlank() || version.isBlank()) return;

            // putIfAbsent: FabricLoader has the actually-running version; prefer it.
            if (installedVersions.putIfAbsent(id, version) == null) {
                LOGGER.debug("Conduit disk-scan: found {} {} in {} (not yet loaded by Fabric)",
                        id, version, jar.getFileName());
            }
        } catch (Exception e) {
            LOGGER.debug("Conduit: could not read fabric.mod.json from {}: {}",
                    jar.getFileName(), e.getMessage());
        }
    }

    /** Returns true if the given modId is currently loaded (any version). */
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
}
