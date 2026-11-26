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

        String java = ProcessHandle.current().info().command().orElseThrow();
        Process p = new ProcessBuilder(java, "-cp", attacherDir.toString(), Attacher.class.getName(),
                Long.toString(ProcessHandle.current().pid()), agentJar.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("Attach failed: " + output.strip());
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
