package com.jacob0225.conduit.restart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RestartHelperTest {

    static String java() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    void refusesSpecFromAnotherProcess(@TempDir Path dir) {
        RestartSpec spec = new RestartSpec(111, dir.toString(), List.of(java(), RestartHelper.KNOT_CLIENT));
        assertNotNull(RestartHelper.validate(spec, Optional.of(222L)));
        assertNotNull(RestartHelper.validate(spec, Optional.empty()));
    }

    @Test
    void refusesNonJavaExecutable(@TempDir Path dir) throws Exception {
        Path fake = Files.writeString(dir.resolve("calc.exe"), "");
        RestartSpec spec = new RestartSpec(5, dir.toString(), List.of(fake.toString(), RestartHelper.KNOT_CLIENT));
        assertEquals("first token is not a Java executable", RestartHelper.validate(spec, Optional.of(5L)));
    }

    @Test
    void refusesCommandWithoutFabric(@TempDir Path dir) {
        RestartSpec spec = new RestartSpec(5, dir.toString(), List.of(java(), "-version"));
        assertEquals("command does not start Fabric's KnotClient", RestartHelper.validate(spec, Optional.of(5L)));
    }

    @Test
    void acceptsValidSpec(@TempDir Path dir) {
        RestartSpec spec = new RestartSpec(5, dir.toString(), List.of(java(), RestartHelper.KNOT_CLIENT));
        assertNull(RestartHelper.validate(spec, Optional.of(5L)));
    }

    // ── end to end: fake game → helper → relaunched process ─────────────────

    @Test
    void relaunchesOnlyAfterGameExitsAndNeverLogsSecrets(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("relaunched.txt");
        // Only the restart classes + these fakes: proves the helper needs nothing from Minecraft.
        String cp = Path.of(RestartHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator
                + Path.of(FakeGame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process game = new ProcessBuilder(java(), "-cp", cp, FakeGame.class.getName(),
                dir.toString(), marker.toString(), cp)
                .redirectErrorStream(true).redirectOutput(dir.resolve("fake-game.out").toFile()).start();
        assertTrue(game.waitFor(30, TimeUnit.SECONDS), "fake game should exit");

        long deadline = System.currentTimeMillis() + 30_000;
        while (!Files.exists(marker) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue(Files.exists(marker), "helper should start the new process; log:\n" + readLog(dir));
        assertEquals("gameAlive=false", Files.readString(marker).trim(),
                "new process must not start while the old game is still running");

        String log = readLog(dir);
        assertTrue(log.contains("started new game pid"), log);
        assertFalse(log.contains(FakeGame.SECRET), "session token must never be logged");
        assertTrue(log.contains("--accessToken <redacted>"), log);
    }

    private static String readLog(Path dir) throws Exception {
        Path log = dir.resolve("logs").resolve("conduit-restart.log");
        return Files.exists(log) ? Files.readString(log) : "(no log)";
    }

    /** Stands in for Minecraft: starts the helper, hands it a spec, then exits a little later. */
    public static final class FakeGame {
        static final String SECRET = "SECRET-TOKEN-123";

        public static void main(String[] args) throws Exception {
            String dir = args[0], marker = args[1], cp = args[2];
            Process helper = new ProcessBuilder(java(), "-cp", cp, RestartHelper.class.getName())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            List<String> command = List.of(java(), "-cp", cp, FakeRelaunched.class.getName(),
                    RestartHelper.KNOT_CLIENT, "--accessToken", SECRET, marker,
                    Long.toString(ProcessHandle.current().pid()));
            try (OutputStream in = helper.getOutputStream()) {
                new RestartSpec(ProcessHandle.current().pid(), dir, command).write(in);
            }
            Thread.sleep(1500); // the helper must wait for this
        }
    }

    /** Stands in for the relaunched game: records whether the old game was still alive. */
    public static final class FakeRelaunched {
        public static void main(String[] args) throws Exception {
            String marker = args[3];
            long oldPid = Long.parseLong(args[4]);
            boolean alive = ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false);
            Files.writeString(Path.of(marker), "gameAlive=" + alive);
        }
    }
}
