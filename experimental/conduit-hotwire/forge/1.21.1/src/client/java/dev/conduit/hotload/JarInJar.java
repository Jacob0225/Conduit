package dev.conduit.hotload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Expands Forge jar-in-jar ({@code META-INF/jarjar/metadata.json}): nested jars are
 * extracted next to the game and returned before their container, recursively, so
 * libraries and dependencies are defined before the mods that use them.
 */
final class JarInJar {
    private static final Pattern PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    private JarInJar() {}

    static List<Path> expand(Path jar, Path extractDir) throws IOException {
        List<Path> out = new ArrayList<>();
        expand(jar, extractDir, out);
        return out;
    }

    private static void expand(Path jar, Path extractDir, List<Path> out) throws IOException {
        List<Path> nested = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var meta = zip.getEntry("META-INF/jarjar/metadata.json");
            if (meta != null) {
                String json;
                try (InputStream in = zip.getInputStream(meta)) {
                    json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Matcher m = PATH.matcher(json);
                while (m.find()) {
                    String entryName = m.group(1);
                    var entry = zip.getEntry(entryName);
                    if (entry == null) continue;
                    String file = Path.of(entryName).getFileName().toString();
                    if (!file.endsWith(".jar") || file.contains("..")) continue;
                    Path target = extractDir.resolve(file);
                    Files.createDirectories(extractDir);
                    if (!Files.exists(target) || Files.size(target) != entry.getSize()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    nested.add(target);
                }
            }
        }
        for (Path n : nested) expand(n, extractDir, out);
        out.add(jar);
    }

    static boolean isMod(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry("META-INF/mods.toml") != null;
        }
    }
}
