package com.jacob0225.conduit.restart;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tiny out-of-process restarter, started by the game right before it shuts down.
 *
 * <ol>
 *   <li>Reads a {@link RestartSpec} from stdin (never from arguments or disk).</li>
 *   <li>Checks the spec came from its own parent and starts Java with Fabric's launcher.</li>
 *   <li>Waits for the old game process to exit, so two games never run at once.</li>
 *   <li>Starts the new game once and exits.</li>
 * </ol>
 *
 * Writes a short, redacted log to {@code logs/conduit-restart.log}. Runs as the
 * current user, starts nothing else, and leaves nothing behind.
 */
public final class RestartHelper {

    static final String KNOT_CLIENT = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    private static final Set<String> JAVA_NAMES = Set.of("java", "java.exe", "javaw", "javaw.exe");
    private static final long EXIT_TIMEOUT_SECONDS = 90;
    private static final long SETTLE_MILLIS = 750;

    private RestartHelper() {}

    public static void main(String[] args) {
        Path log = null;
        try {
            RestartSpec spec = RestartSpec.read(System.in);
            Path workDir = Path.of(spec.workDir());
            log = workDir.resolve("logs").resolve("conduit-restart.log");
            log(log, "helper started for game pid " + spec.parentPid());

            String problem = validate(spec, ProcessHandle.current().parent().map(ProcessHandle::pid));
            if (problem != null) {
                log(log, "refused: " + problem);
                System.exit(3);
            }

            Optional<ProcessHandle> game = ProcessHandle.of(spec.parentPid());
            if (game.isPresent()) {
                try {
                    game.get().onExit().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // Never start a second copy next to one that is still running.
                    log(log, "game did not exit within " + EXIT_TIMEOUT_SECONDS + "s; not relaunching");
                    System.exit(2);
                }
            }
            Thread.sleep(SETTLE_MILLIS); // let the OS release file locks on mods/ and options.txt

            ProcessBuilder builder = new ProcessBuilder(spec.command())
                    .directory(workDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            log(log, "started new game pid " + process.pid() + ": " + LaunchCommand.redact(spec.command()));
            System.exit(0);
        } catch (Throwable t) {
            if (log != null) {
                StringWriter trace = new StringWriter();
                t.printStackTrace(new PrintWriter(trace));
                log(log, "failed: " + trace);
            }
            System.exit(1);
        }
    }

    /** Returns why the spec must not run, or null when it is acceptable. */
    static String validate(RestartSpec spec, Optional<Long> actualParent) {
        if (actualParent.isEmpty() || actualParent.get() != spec.parentPid()) {
            return "spec did not come from this helper's parent process";
        }
        Path workDir = Path.of(spec.workDir());
        if (!Files.isDirectory(workDir)) return "game directory does not exist";
        Path java = Path.of(spec.command().get(0));
        String name = java.getFileName() == null ? "" : java.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!JAVA_NAMES.contains(name) || !Files.isRegularFile(java)) {
            return "first token is not a Java executable";
        }
        if (!spec.command().contains(KNOT_CLIENT)) return "command does not start Fabric's KnotClient";
        return null;
    }

    private static void log(Path file, String message) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging is best-effort; the restart itself matters more.
        }
    }
}
