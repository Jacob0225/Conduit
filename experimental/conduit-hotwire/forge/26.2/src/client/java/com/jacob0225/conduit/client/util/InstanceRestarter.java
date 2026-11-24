package com.jacob0225.conduit.client.util;

import com.jacob0225.conduit.client.PlatformPaths;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Relaunches the running Minecraft instance by replaying the JVM's own command
 * line, then shutting this process down.
 *
 * <p>Used after Conduit changes which mods are in {@code mods/} (a fresh install
 * or a server switch), since Fabric only reads the folder at startup — an
 * in-process reconnect would still load the old set.
 *
 * <p>Strategy: capture {@link ProcessHandle#info()#commandLine()} of the current
 * process, spawn an identical process, then stop the game. Replaying the real
 * command line (classpath, JVM args, natives, main class, everything) is the
 * only reliable way to relaunch across vanilla, Prism, ATLauncher, MultiMC, etc.
 * If the command line can't be read, we just exit so the player can restart from
 * their launcher manually.
 */
public final class InstanceRestarter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Restart");

    private InstanceRestarter() {}

    /**
     * True when this JVM's own command line can be read, so {@link #restart()}
     * will relaunch the game rather than only closing it. Windows does not
     * expose another process's arguments, so this is usually false there.
     */
    public static boolean canRelaunch() {
        return !relaunchCommand().isEmpty();
    }

    /** Relaunch (when possible) and tear down the current instance. */
    public static void restart() {
        LOGGER.info("Restarting instance to load updated Conduit mod set");

        List<String> argv = relaunchCommand();
        if (!argv.isEmpty()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(argv);
                pb.directory(PlatformPaths.gameDir().toFile());
                pb.inheritIO();
                pb.start();
                LOGGER.info("Spawned restart process ({} tokens)", argv.size());
            } catch (IOException e) {
                LOGGER.error("Failed to spawn restart process: {} — exiting only.", e.getMessage());
            }
        } else {
            LOGGER.warn("Could not read this process's command line — exiting only; "
                    + "restart the instance from your launcher to load the new mods.");
        }

        // Tear down this JVM either way.
        Minecraft mc = Minecraft.getInstance();
        mc.setScreenAndShow(null);
        mc.stop();
    }

    /**
     * Prefers the structured executable + argument array. Re-splitting the
     * joined {@code commandLine()} string broke any argument containing a
     * space (install paths, player names, quoted JVM options), because the JDK
     * joins arguments with single spaces and no quoting.
     */
    private static List<String> relaunchCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> command = info.command();
        Optional<String[]> arguments = info.arguments();
        if (command.isPresent() && arguments.isPresent()) {
            List<String> argv = new ArrayList<>();
            argv.add(command.get());
            argv.addAll(List.of(arguments.get()));
            return argv;
        }
        return info.commandLine().map(InstanceRestarter::shellSplit).orElse(List.of());
    }

    /**
     * Splits a command-line string into argv tokens, honouring double-quoted
     * segments. Single quotes and escaping aren't handled — JVM command lines
     * produced by Minecraft launchers don't use them.
     */
    private static List<String> shellSplit(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }
}
