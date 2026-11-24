package com.jacob0225.conduit.client.compat;

import com.google.gson.JsonParser;
import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;

/** Compares actual Hotwire runtime state, rather than treating files as loaded mods. */
public final class HotwireServerSwitch {
    private record JarMod(Path path, String id, String version) {}
    private record Changes(List<Path> jars, List<String> off, List<String> on) {
        boolean empty() { return jars.isEmpty() && off.isEmpty() && on.isEmpty(); }
    }

    private static Path game() { return FabricLoader.getInstance().getGameDir(); }

    private static Path safe(Path directory, String filename) throws IOException {
        if (filename.isBlank() || !Path.of(filename).getFileName().toString().equals(filename))
            throw new IOException("Invalid managed jar filename");
        Path path = directory.resolve(filename).normalize();
        if (!path.getParent().equals(directory.normalize())) throw new IOException("Invalid managed jar path");
        return path;
    }

    private static Path find(String filename) throws IOException {
        Path mods = safe(game().resolve("mods"), filename);
        if (Files.isRegularFile(mods)) return mods;
        for (var record : ServerInstallRecord.allRecords()) {
            Path stash = safe(game().resolve(ModStashManager.STASH_DIR).resolve(record.key().stem()), filename);
            if (Files.isRegularFile(stash)) return stash;
        }
        throw new IOException("Managed jar is missing: " + filename);
    }

    private static JarMod read(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) throw new IOException("Managed jar is not a Fabric mod: " + path.getFileName());
            try (var reader = new java.io.InputStreamReader(jar.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                return new JarMod(path, json.get("id").getAsString(), json.get("version").getAsString());
            }
        }
    }

    private static Changes changes(ServerInstallRecord target) throws Exception {
        Map<String, JarMod> wanted = new TreeMap<>();
        for (String filename : target.ownedJarFilenames()) {
            JarMod mod = read(find(filename));
            if (wanted.putIfAbsent(mod.id(), mod) != null) throw new IOException("Duplicate target mod " + mod.id());
        }
        Set<String> managed = new HashSet<>();
        for (var record : ServerInstallRecord.allRecords()) {
            for (String filename : record.managedJarFilenames()) {
                try { managed.add(read(find(filename)).id()); }
                catch (IOException e) {
                    // Historical, superseded jars may no longer exist. Current target jars above may not.
                }
            }
        }
        Map<String, HotwireBridge.RunningMod> running = new HashMap<>();
        for (var mod : HotwireBridge.runningMods()) running.put(mod.id(), mod);
        List<String> off = running.values().stream()
                .filter(mod -> mod.on() && managed.contains(mod.id()) && !wanted.containsKey(mod.id()))
                .map(HotwireBridge.RunningMod::id).sorted().toList();
        List<String> on = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        for (var mod : wanted.values()) {
            var current = running.get(mod.id());
            if (current == null || !current.version().equals(mod.version())) jars.add(mod.path());
            else if (!current.on()) on.add(mod.id());
        }
        return new Changes(jars, off, on);
    }

    /** True when every jar's mod is running and enabled at that jar's version. */
    public static boolean running(List<Path> jars) {
        if (jars.isEmpty()) return false;
        try {
            Map<String, HotwireBridge.RunningMod> running = new HashMap<>();
            for (var mod : HotwireBridge.runningMods()) running.put(mod.id(), mod);
            for (Path jar : jars) {
                JarMod mod = read(jar);
                var current = running.get(mod.id());
                if (current == null || !current.on() || !current.version().equals(mod.version())) return false;
            }
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    public static boolean matches(ServerInstallRecord target) {
        try { return changes(target).empty(); }
        catch (Exception | LinkageError e) { return false; }
    }

    public static CompletableFuture<HotwireBridge.Plan> plan(ServerInstallRecord target) throws Exception {
        Changes changes = changes(target);
        return HotwireBridge.plan(changes.jars(), changes.off(), changes.on());
    }

    /** Only align restart-time files after Hotwire has finished the accepted runtime change. */
    public static void finish(ServerInstallRecord target) throws Exception {
        if (!matches(target)) throw new IOException("Hotwire runtime does not match the target server");
        prepareRestart(target);
    }

    public static void prepareRestart(ServerInstallRecord target) throws Exception {
        Path mods = game().resolve("mods");
        Files.createDirectories(mods);
        for (String filename : target.ownedJarFilenames()) {
            Path dest = safe(mods, filename);
            if (!Files.isRegularFile(dest)) Files.copy(find(filename), dest, StandardCopyOption.REPLACE_EXISTING);
        }
        new ModStashManager().reconcile(target.key(), target.ownedJarFilenames());
        for (String filename : target.ownedJarFilenames()) {
            if (!Files.isRegularFile(safe(mods, filename))) throw new IOException("Target jar was not restored");
        }
        for (var record : ServerInstallRecord.allRecords()) {
            for (String filename : record.managedJarFilenames()) {
                if (!target.ownedJarFilenames().contains(filename) && Files.exists(safe(mods, filename)))
                    throw new IOException("Previous server jar could not be stashed: " + filename);
            }
        }
    }

    private HotwireServerSwitch() {}
}
