package com.jacob0225.conduit.restart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaunchCommandTest {

    private static final String KNOT = RestartHelper.KNOT_CLIENT;
    private static final List<String> GAME = List.of(
            "--username", "Steve", "--gameDir", "C:\\Users\\me\\Fabric 26.3 (3)",
            "--accessToken", "SECRET-TOKEN", "--uuid", "abc123", "--version", "26.3");

    @Test
    void buildsKnotCommandWithMarkerAndClasspath() {
        List<String> cmd = LaunchCommand.build("java", List.of("-Xmx4G"), "a.jar;b.jar", null,
                KNOT, GAME, null, 42L);
        assertEquals("java", cmd.get(0));
        assertTrue(cmd.contains("-Xmx4G"));
        assertTrue(cmd.contains("-D" + LaunchCommand.RESTARTED_AT + "=42"));
        int cp = cmd.indexOf("-cp");
        assertEquals("a.jar;b.jar", cmd.get(cp + 1));
        assertEquals(KNOT, cmd.get(cp + 2));
        // Paths with spaces stay one token.
        assertTrue(cmd.contains("C:\\Users\\me\\Fabric 26.3 (3)"));
    }

    @Test
    void usesArgfileInsteadOfClasspathWhenGiven() {
        List<String> cmd = LaunchCommand.build("java", List.of(), "x.jar", "C:\\g\\cp.args", KNOT, GAME, null, 1L);
        assertFalse(cmd.contains("-cp"));
        assertTrue(cmd.contains("@C:\\g\\cp.args"));
    }

    @Test
    void sanitizeDropsClasspathLauncherPropsAndOldMarker() {
        List<String> out = LaunchCommand.sanitizeJvmArgs(List.of(
                "-Xmx2G", "-cp", "old.jar", "-Dtheseus.foo=1", "-Dmodrinth.x=y",
                "-D" + LaunchCommand.RESTARTED_AT + "=5", "-Djava.library.path=natives", "-classpath", "x"));
        assertEquals(List.of("-Xmx2G", "-Djava.library.path=natives"), out);
    }

    @Test
    void reconnectReplacesExistingAutoJoinButKeepsQuickPlayPath() {
        List<String> args = List.of("--quickPlayPath", "qp.json", "--quickPlayMultiplayer", "old:1",
                "--server", "s", "--port", "2", "--version", "26.3");
        List<String> out = LaunchCommand.withReconnect(args, "play.example.com:25565");
        assertEquals(List.of("--quickPlayPath", "qp.json", "--version", "26.3",
                "--quickPlayMultiplayer", "play.example.com:25565"), out);
    }

    @Test
    void noReconnectStripsAutoJoin() {
        List<String> out = LaunchCommand.stripQuickPlay(List.of("--quickPlaySingleplayer", "World", "--demo"));
        assertEquals(List.of("--demo"), out);
    }

    @Test
    void addressBracketsIpv6() {
        assertEquals("example.com:25565", LaunchCommand.address("example.com", 25565));
        assertEquals("[::1]:25565", LaunchCommand.address("::1", 25565));
    }

    @Test
    void redactHidesSecretsAccountIdsAndClasspath() {
        List<String> cmd = LaunchCommand.build("java", List.of("-Dauth.session.token=xyz"), "a.jar", null,
                KNOT, GAME, null, 1L);
        String log = LaunchCommand.redact(cmd);
        assertFalse(log.contains("SECRET-TOKEN"));
        assertFalse(log.contains("abc123"));
        assertFalse(log.contains("Steve"));
        assertFalse(log.contains("xyz"));
        assertFalse(log.contains("a.jar"));
        assertTrue(log.contains("--accessToken <redacted>"));
        assertTrue(log.contains("-cp <1 entries>"));
    }

    @Test
    void argfileEscapesBackslashesAndQuotes() {
        assertEquals("-cp\n\"C:\\\\a b\\\\x.jar\"\n", LaunchCommand.classpathArgfile("C:\\a b\\x.jar"));
    }
}
