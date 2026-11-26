package dev.conduit.hotload.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Gets {@link Instrumentation} for the running game and uses it to read and
 * replace the bytecode of classes that are already loaded.
 *
 * <p>How: write a tiny agent jar (just {@link AgentMain}) to a temp folder, start a
 * helper JVM that attaches to this process and loads it. Needs the runtime to
 * include the {@code jdk.attach} module and dynamic agent loading not to be
 * disabled ({@code -XX:-EnableDynamicAgentLoading}).
 */
public final class JvmAccess {

    private static Instrumentation instrumentation;

    private JvmAccess() {}

    public static synchronized Instrumentation instrumentation() throws Exception {
        if (instrumentation != null) return instrumentation;
        Object existing = System.getProperties().get(AgentMain.PROPERTY);
        if (!(existing instanceof Instrumentation)) {
            attach();
            existing = System.getProperties().get(AgentMain.PROPERTY);
        }
        if (!(existing instanceof Instrumentation inst)) throw new IllegalStateException("Agent did not start");
        instrumentation = inst;
        return inst;
    }

    private static void attach() throws Exception {
        Path dir = Files.createTempDirectory("conduit-hotload");
        Path agentJar = dir.resolve("conduit-hotload-agent.jar");
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Agent-Class", AgentMain.class.getName());
        main.putValue("Can-Redefine-Classes", "true");
        main.putValue("Can-Retransform-Classes", "true");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(agentJar), manifest)) {
            copyClass(jar, AgentMain.class);
        }
        Path attacherDir = dir.resolve("attacher");
        writeClass(attacherDir, Attacher.class);

        String java = attachJava();
        if (java == null) throw new IllegalStateException("No Java with the jdk.attach module was found");
        Process p = new ProcessBuilder(java, "-cp", attacherDir.toString(), Attacher.class.getName(),
                Long.toString(ProcessHandle.current().pid()), agentJar.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("Attach failed: " + output.strip());
        }
    }

    private static String attachJava;
    private static boolean searched;

    /** True when live loading can work here: the agent is already in, or a Java that can attach exists. */
    public static synchronized boolean canAttach() {
        return System.getProperties().get(AgentMain.PROPERTY) instanceof Instrumentation || attachJava() != null;
    }

    /**
     * A java executable whose runtime has {@code jdk.attach}. The game's own runtime first; launchers
     * often ship a trimmed one without it (Modrinth's does), so then look for another installed Java.
     * The attacher only needs to match the platform, not the game's Java version.
     */
    static synchronized String attachJava() {
        if (searched) return attachJava;
        searched = true;
        if (java.lang.module.ModuleFinder.ofSystem().find("jdk.attach").isPresent()) {
            return attachJava = ProcessHandle.current().info().command().orElse(null);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String exe = windows ? "java.exe" : "java";
        java.util.LinkedHashSet<Path> homes = new java.util.LinkedHashSet<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) homes.add(Path.of(javaHome));
        Path user = Path.of(System.getProperty("user.home"));
        java.util.List<Path> roots = new java.util.ArrayList<>(java.util.List.of(user.resolve(".jdks")));
        if (windows) {
            String appData = System.getenv("APPDATA"), local = System.getenv("LOCALAPPDATA");
            if (appData != null) roots.add(Path.of(appData, ".minecraft", "runtime"));
            if (local != null) roots.add(Path.of(local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"));
            for (String pf : java.util.List.of("C:/Program Files/Java", "C:/Program Files/Eclipse Adoptium",
                    "C:/Program Files/Zulu", "C:/Program Files/Microsoft", "C:/Program Files (x86)/Minecraft Launcher/runtime")) {
                roots.add(Path.of(pf));
            }
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            roots.add(user.resolve(".minecraft").resolve("runtime"));
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root, 5)) {
                walk.filter(f -> f.getFileName().toString().equals(exe) && f.getParent().getFileName().toString().equals("bin"))
                        .forEach(f -> homes.add(f.getParent().getParent()));
            } catch (IOException ignored) {
                // unreadable folder: skip
            }
        }
        for (Path home : homes) {
            Path java = home.resolve("bin").resolve(exe);
            if (Files.isRegularFile(java) && hasAttach(java)) return attachJava = java.toString();
        }
        return null;
    }

    private static boolean hasAttach(Path java) {
        try {
            Process p = new ProcessBuilder(java.toString(), "--list-modules").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            return p.waitFor(10, TimeUnit.SECONDS) && out.contains("jdk.attach@");
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] bytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing " + resource);
            return in.readAllBytes();
        }
    }

    private static void copyClass(JarOutputStream jar, Class<?> type) throws IOException {
        jar.putNextEntry(new JarEntry(type.getName().replace('.', '/') + ".class"));
        jar.write(bytes(type));
        jar.closeEntry();
    }

    private static void writeClass(Path root, Class<?> type) throws IOException {
        Path file = root.resolve(type.getName().replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) { out.write(bytes(type)); }
    }

    /** The class's bytecode exactly as currently loaded (after every transformer and mixin). */
    public static byte[] currentBytes(Class<?> type) throws Exception {
        Instrumentation inst = instrumentation();
        byte[][] captured = new byte[1][];
        ClassFileTransformer capture = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] buffer) {
                if (redefined == type) captured[0] = buffer.clone();
                return null;
            }
        };
        inst.addTransformer(capture, true);
        try {
            inst.retransformClasses(type);
        } finally {
            inst.removeTransformer(capture);
        }
        if (captured[0] == null) throw new IllegalStateException("Could not read bytes of " + type.getName());
        return captured[0];
    }

    /** Swaps method bodies of a loaded class. Same fields, methods and supertypes only. */
    public static void redefine(Class<?> type, byte[] bytes) throws Exception {
        instrumentation().redefineClasses(new ClassDefinition(type, bytes));
    }

    /** Lets {@code from} see {@code to} (needed when patched game code calls into Conduit's module). */
    public static void addReads(Module from, Module to) throws Exception {
        if (from.canRead(to)) return;
        instrumentation().redefineModule(from, java.util.Set.of(to), java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(), java.util.Map.of());
    }
}
