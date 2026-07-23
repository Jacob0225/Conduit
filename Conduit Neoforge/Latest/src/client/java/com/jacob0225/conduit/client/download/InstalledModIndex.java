package com.jacob0225.conduit.client.download;

import com.jacob0225.conduit.client.PlatformPaths;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/** Snapshot of loaded and newly installed NeoForge mods. */
public class InstalledModIndex {
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([a-z0-9_-]+)[\"']");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");
    private final Map<String, String> installedVersions = new HashMap<>();

    public InstalledModIndex() {
        ModList.get().getMods().forEach(mod ->
                installedVersions.put(mod.getModId(), mod.getVersion().toString()));
        scanModsDirectory();
    }

    public boolean isInstalled(String modId) { return installedVersions.containsKey(modId); }

    public Optional<String> getInstalledVersion(String modId) {
        return Optional.ofNullable(installedVersions.get(modId));
    }

    public boolean hasSatisfyingVersion(String modId, String requiredVersion) {
        String installed = installedVersions.get(modId);
        if (installed == null) return false;
        if (installed.equals(requiredVersion)) return true;
        String normInstalled = stripBuildMeta(installed);
        String normRequired = stripBuildMeta(requiredVersion);
        return normInstalled.equals(normRequired)
                || installed.contains(requiredVersion) || requiredVersion.contains(installed)
                || normInstalled.contains(normRequired) || normRequired.contains(normInstalled);
    }

    private static String stripBuildMeta(String version) {
        int first = version.indexOf('+');
        if (first < 0) return version;
        int second = version.indexOf('+', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    private void scanModsDirectory() {
        Path modsDir = PlatformPaths.gameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return;
        try (var stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            stream.forEach(this::readNeoForgeMetadata);
        } catch (IOException ignored) {
        }
    }

    private void readNeoForgeMetadata(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) entry = jar.getEntry("META-INF/mods.toml");
            if (entry == null) return;
            String toml;
            try (InputStream in = jar.getInputStream(entry)) {
                toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher id = MOD_ID.matcher(toml);
            Matcher version = VERSION.matcher(toml);
            if (id.find() && version.find() && !version.group(1).contains("${")) {
                installedVersions.putIfAbsent(id.group(1), version.group(1));
            }
        } catch (IOException ignored) {
        }
    }
}
