package com.jacob0225.conduit.client.util;

import com.jacob0225.conduit.restart.LaunchCommand;
import com.jacob0225.conduit.restart.RestartHelper;
import com.jacob0225.conduit.restart.RestartSpec;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * Restarts Minecraft after Conduit changes {@code mods/} (Fabric only reads the
 * folder at startup).
 *
 * <p>The game starts Conduit's bundled {@link RestartHelper} in a separate JVM,
 * hands it the relaunch command over stdin, then shuts down normally. The
 * helper waits for this process to exit and starts the game again. See
 * {@code docs/RESTART.md} for launcher support and the security model.
 *
 * <p>Only ever runs after the player presses a restart button. If anything
 * needed for a safe relaunch is missing, {@link #canRelaunch()} is false and the
 * UI asks the player to reopen the game themselves.
 */
public final class InstanceRestarter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Restart");
    private static final String KNOT_CLIENT = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    /** A game started by a Conduit restart less than this long ago may not restart again. */
    private static final long LOOP_GUARD_MS = 20_000;
    private static final int WINDOWS_COMMAND_LIMIT = 30_000;
    private static final List<String> HELPER_CLASSES = List.of(
            "com/jacob0225/conduit/restart/RestartHelper.class",
            "com/jacob0225/conduit/restart/RestartSpec.class",
            "com/jacob0225/conduit/restart/LaunchCommand.class");

    private static final AtomicBoolean RESTARTING = new AtomicBoolean();

    public enum Result { STARTED, UNSUPPORTED, FAILED, ALREADY_RESTARTING }

    private InstanceRestarter() {}

    /** True when Conduit can reopen the game by itself; otherwise the player reopens it. */
    public static boolean canRelaunch() {
        return unsupportedReason() == null;
    }

    /** Why automatic relaunch is off, or null when it is available. */
    public static String unsupportedReason() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return "development environment";
        if (helperJar().isEmpty()) return "Conduit is not running from a jar";
        if (javaExecutable() == null) return "the Java executable could not be found";
        if (jvmArguments() == null) return "the JVM's arguments could not be read";
        String cp = System.getProperty("java.class.path", "");
        if (cp.isBlank()) return "the classpath is unavailable";
        if (ClassLoader.getSystemClassLoader().getResource(KNOT_CLIENT.replace('.', '/') + ".class") == null) {
            return "Fabric's launcher class is not on the classpath";
        }
        String at = System.getProperty(LaunchCommand.RESTARTED_AT);
        if (at != null) {
            try {
                if (System.currentTimeMillis() - Long.parseLong(at) < LOOP_GUARD_MS) return "the game just restarted";
            } catch (NumberFormatException ignored) {
                // A malformed marker is treated as absent.
            }
        }
        return null;
    }

    /**
     * Starts the helper and shuts the game down. Call on the render thread, and
     * only after the player confirmed. {@code reconnect} is {@code host:port} to
     * rejoin after the restart, or null.
     */
    public static Result restart(String reconnect) {
        String reason = unsupportedReason();
        if (reason != null) {
            LOGGER.warn("Automatic restart unavailable ({}); closing so the player can reopen the game", reason);
            stopGame();
            return Result.UNSUPPORTED;
        }
        if (!RESTARTING.compareAndSet(false, true)) return Result.ALREADY_RESTARTING;
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            List<String> command = buildCommand(gameDir, reconnect);
            Path jar = helperJar().orElseThrow();
            verifyHelper(jar);

            List<String> helper = List.of(javaExecutable(), "-Xmx64m", "-cp", jar.toString(),
                    RestartHelper.class.getName());
            Process process = new ProcessBuilder(helper)
                    .directory(gameDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (OutputStream stdin = process.getOutputStream()) {
                new RestartSpec(ProcessHandle.current().pid(), gameDir.toString(), command).write(stdin);
            }
            LOGGER.info("Restart helper started (pid {}); relaunch command: {}", process.pid(),
                    LaunchCommand.redact(command));
        } catch (Exception e) {
            RESTARTING.set(false);
            LOGGER.error("Could not start the restart helper; the game keeps running", e);
            return Result.FAILED;
        }
        stopGame();
        return Result.STARTED;
    }

    private static void stopGame() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreenAndShow(null);
        mc.stop(); // normal shutdown: saves options and any open world
    }

    static List<String> buildCommand(Path gameDir, String reconnect) throws IOException {
        String classpath = System.getProperty("java.class.path");
        String java = javaExecutable();
        List<String> jvm = jvmArguments();
        List<String> game = Arrays.asList(FabricLoader.getInstance().getLaunchArguments(false));
        long now = System.currentTimeMillis();

        List<String> command = LaunchCommand.build(java, jvm, classpath, null, KNOT_CLIENT, game, reconnect, now);
        if (isWindows() && LaunchCommand.commandLineLength(command) > WINDOWS_COMMAND_LIMIT) {
            // Too long for one Windows command line: move only the classpath into an @argfile.
            Path argfile = gameDir.resolve(".conduit").resolve("restart-classpath.args");
            Files.createDirectories(argfile.getParent());
            Files.writeString(argfile, LaunchCommand.classpathArgfile(classpath));
            command = LaunchCommand.build(java, jvm, classpath, argfile.toString(), KNOT_CLIENT, game, reconnect, now);
        }
        return command;
    }

    /** The JVM's own options (heap, natives path, launcher system properties). Works on Windows too. */
    private static List<String> jvmArguments() {
        try {
            return List.copyOf(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Same Java the game runs on (javaw on Windows, so no console window opens). */
    private static String javaExecutable() {
        Optional<String> current = ProcessHandle.current().info().command();
        if (current.isPresent() && Files.isRegularFile(Path.of(current.get()))) return current.get();
        Path bin = Path.of(System.getProperty("java.home", ""), "bin");
        for (String name : isWindows() ? List.of("javaw.exe", "java.exe") : List.of("java")) {
            if (Files.isRegularFile(bin.resolve(name))) return bin.resolve(name).toString();
        }
        return null;
    }

    private static Optional<Path> helperJar() {
        return FabricLoader.getInstance().getModContainer("conduit")
                .flatMap(mod -> mod.getOrigin().getPaths().stream()
                        .filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p))
                        .findFirst());
    }

    /**
     * The helper runs from Conduit's jar on disk. Make sure that jar still holds
     * exactly the helper code this game loaded, so a swapped file is never run.
     */
    private static void verifyHelper(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String name : HELPER_CLASSES) {
                var entry = zip.getEntry(name);
                if (entry == null) throw new IOException("Helper class missing from " + jar.getFileName());
                byte[] onDisk;
                try (InputStream in = zip.getInputStream(entry)) { onDisk = in.readAllBytes(); }
                byte[] loaded;
                try (InputStream in = InstanceRestarter.class.getClassLoader().getResourceAsStream(name)) {
                    if (in == null) throw new IOException("Loaded helper class not found: " + name);
                    loaded = in.readAllBytes();
                }
                if (!MessageDigest.isEqual(sha256(onDisk), sha256(loaded))) {
                    throw new IOException("Helper class on disk differs from the loaded one: " + name);
                }
            }
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
