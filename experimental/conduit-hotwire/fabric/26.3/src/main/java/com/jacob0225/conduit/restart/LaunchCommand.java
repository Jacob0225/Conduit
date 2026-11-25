package com.jacob0225.conduit.restart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the command that starts Minecraft again, and redacts it for logs.
 * Pure functions only, so the rules are unit-tested without a game running.
 *
 * <p>Approach adapted from Reload Everything (MIT, see THIRD_PARTY_NOTICES.md):
 * launch Fabric's {@code KnotClient} directly instead of the launcher's own
 * wrapper class, and drop launcher-private JVM properties. Unlike it, Conduit
 * takes the game arguments from Fabric (exact tokens, so paths with spaces
 * survive) and never logs session arguments.
 */
public final class LaunchCommand {

    /** Marker added to the new game's JVM, holding the restart time (epoch ms). */
    public static final String RESTARTED_AT = "conduit.restart.at";

    /** Game arguments whose values are secrets or account identifiers. */
    private static final Set<String> SENSITIVE_GAME_ARGS = Set.of(
            "--accesstoken", "--uuid", "--xuid", "--clientid", "--userproperties",
            "--profileproperties", "--session", "--username");

    private static final Set<String> CLASSPATH_FLAGS = Set.of("-cp", "-classpath", "--class-path");

    /** Arguments that make the game join something on start ({@code --quickPlayPath} is just a log file). */
    private static final Set<String> AUTO_JOIN_ARGS = Set.of(
            "--quickplaysingleplayer", "--quickplaymultiplayer", "--quickplayrealms", "--server", "--port");

    private LaunchCommand() {}

    /**
     * The full relaunch command.
     *
     * @param classpathArg either {@code -cp <path>} tokens (when {@code argfile} is null) or
     *                     used as-is through an {@code @argfile} holding the classpath
     * @param reconnect    {@code host:port} to rejoin with Quick Play, or null
     */
    public static List<String> build(String javaExe, List<String> jvmArgs, String classpath, String argfile,
                                     String mainClass, List<String> gameArgs, String reconnect, long now) {
        List<String> command = new ArrayList<>();
        command.add(javaExe);
        command.addAll(sanitizeJvmArgs(jvmArgs));
        command.add("-D" + RESTARTED_AT + "=" + now);
        if (argfile != null) {
            command.add("@" + argfile);
        } else {
            command.add("-cp");
            command.add(classpath);
        }
        command.add(mainClass);
        command.addAll(reconnect == null ? stripQuickPlay(gameArgs) : withReconnect(gameArgs, reconnect));
        return command;
    }

    /**
     * Removes the classpath (re-added explicitly), launcher-private properties
     * (the launcher's wrapper is bypassed), and any previous restart marker.
     */
    public static List<String> sanitizeJvmArgs(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (CLASSPATH_FLAGS.contains(arg)) { i++; continue; }
            if (arg.startsWith("-Dtheseus") || arg.startsWith("-Dmodrinth")
                    || arg.startsWith("-D" + RESTARTED_AT + "=")) continue;
            out.add(arg);
        }
        return out;
    }

    /** Game args with any Quick Play / auto-connect target removed. */
    public static List<String> stripQuickPlay(List<String> gameArgs) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < gameArgs.size(); i++) {
            String arg = gameArgs.get(i);
            if (AUTO_JOIN_ARGS.contains(arg.toLowerCase(Locale.ROOT))) {
                i++; // and its value
                continue;
            }
            out.add(arg);
        }
        return out;
    }

    /** Game args that rejoin {@code address} ({@code host:port}) once the game has loaded. */
    public static List<String> withReconnect(List<String> gameArgs, String address) {
        List<String> out = stripQuickPlay(gameArgs);
        out.add("--quickPlayMultiplayer");
        out.add(address);
        return out;
    }

    /** {@code host:port}, bracketing IPv6 literals. */
    public static String address(String host, int port) {
        return (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
    }

    /**
     * Contents of a Java {@code @argfile} that sets the classpath. Used on
     * Windows when the command would exceed the 32k command-line limit. Holds
     * only library paths, never session arguments.
     */
    public static String classpathArgfile(String classpath) {
        String escaped = classpath.replace("\\", "\\\\").replace("\"", "\\\"");
        return "-cp\n\"" + escaped + "\"\n";
    }

    /** Rough Windows command-line length for {@code command}. */
    public static int commandLineLength(List<String> command) {
        int length = 0;
        for (String token : command) length += token.length() + 3;
        return length;
    }

    /** The command with secrets, account ids and the classpath replaced, safe to log. */
    public static String redact(List<String> command) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            String lower = arg.toLowerCase(Locale.ROOT);
            if (out.length() > 0) out.append(' ');
            if (SENSITIVE_GAME_ARGS.contains(lower) && i + 1 < command.size()) {
                out.append(arg).append(" <redacted>");
                i++;
            } else if (CLASSPATH_FLAGS.contains(arg) && i + 1 < command.size()) {
                String cp = command.get(++i);
                int entries = cp.isEmpty() ? 0 : cp.split(java.io.File.pathSeparator).length;
                out.append(arg).append(" <").append(entries).append(" entries>");
            } else if (arg.startsWith("-D") && isSensitiveKey(arg)) {
                int eq = arg.indexOf('=');
                out.append(eq > 0 ? arg.substring(0, eq + 1) + "<redacted>" : arg);
            } else {
                out.append(arg);
            }
        }
        return out.toString();
    }

    private static boolean isSensitiveKey(String property) {
        int eq = property.indexOf('=');
        String key = (eq > 0 ? property.substring(2, eq) : property.substring(2)).toLowerCase(Locale.ROOT);
        return key.contains("token") || key.contains("password") || key.contains("secret") || key.contains("session");
    }
}
