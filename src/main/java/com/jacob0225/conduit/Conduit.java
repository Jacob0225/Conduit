package com.jacob0225.conduit;

import com.jacob0225.conduit.http.ConduitHttpServer;
import com.jacob0225.conduit.manifest.ServerManifestProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server-side (and common) entrypoint for Conduit.
 *
 * <p><b>Architecture.</b> The manifest is delivered to clients over a separate
 * plain-HTTP endpoint, <i>not</i> via a Minecraft packet. This is deliberate:
 * Minecraft's registry sync runs during the configuration phase, so any manifest
 * packet sent inside the game connection would race (and lose to) the registry
 * packets. By serving the manifest out-of-band, the client can install missing
 * mods <b>before</b> it ever opens the game connection — eliminating the
 * "mismatched registry" disconnect.
 *
 * <p>The client side intercepts {@code ConnectScreen.startConnecting} (its
 * single join entry point), fetches the manifest from this HTTP endpoint,
 * installs what's needed, and only then proceeds with the real connection.
 *
 * <p>Lifecycle: the manifest is (re)loaded and the HTTP server (re)started on
 * every {@code SERVER_STARTING}, and both are torn down on
 * {@code SERVER_STOPPED}. Config lives at {@code config/conduit.properties}.
 */
public class Conduit implements ModInitializer {

    public static final String MOD_ID = "conduit";
    public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

    /** Default manifest port = game port + 1 (e.g. 25565 → 25566). */
    public static final int DEFAULT_PORT_OFFSET = 1;

    private ConduitHttpServer httpServer;
    private ServerManifestProvider manifestProvider;

    @Override
    public void onInitialize() {
        LOGGER.info("Conduit initializing (HTTP manifest mode)");

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);

        LOGGER.info("Conduit server-side ready. Manifest will be served over HTTP.");
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    private void onServerStarting(MinecraftServer server) {
        LOGGER.info("════════ CONDUIT SERVER STARTING ════════");
        Path configDir = server.getServerDirectory().resolve("config");
        String mcVersion = server.getServerVersion();
        LOGGER.info("Server directory: {}", server.getServerDirectory());
        LOGGER.info("Config directory: {}", configDir);
        LOGGER.info("Minecraft version: {}", mcVersion);

        // Load the manifest so the HTTP endpoint has something to serve.
        LOGGER.info("Loading manifest provider...");
        manifestProvider = new ServerManifestProvider(configDir, mcVersion);
        manifestProvider.load();
        LOGGER.info("Manifest provider loaded. Will serve {} mod(s).",
                manifestProvider.getPayload().mods().size());

        // Determine the HTTP port: explicit override in conduit.properties, else
        // default to (game port + 1). server.getPort() is unreliable here: during
        // SERVER_STARTING it can return -1 (not yet bound), which would compute an
        // HTTP port of 0 (the OS picks a random ephemeral port the client can never
        // guess). Read the configured port straight from server.properties instead,
        // falling back to server.getPort() and then the Minecraft default.
        int gamePort = readGamePortFromProperties(server.getServerDirectory());
        if (gamePort <= 0) {
            int fallback = server.getPort();
            LOGGER.warn("⚠ Could not read a valid server-port from server.properties; " +
                    "falling back to server.getPort() = {}", fallback);
            gamePort = fallback > 0 ? fallback : 25565;
        }
        LOGGER.info("Game server port (resolved): {}", gamePort);
        int httpPort = resolveHttpPort(configDir, gamePort);
        LOGGER.info("Resolved HTTP manifest port: {}", httpPort);

        httpServer = new ConduitHttpServer(manifestProvider, httpPort);
        httpServer.start();
        LOGGER.info("════════ CONDUIT SERVER STARTED ════════");
    }

    private void onServerStopped(MinecraftServer server) {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    // ── Game port ────────────────────────────────────────────────────────────

    /**
     * Read {@code server-port} from {@code server.properties} on disk. This is
     * the actual, configured Minecraft port — unlike {@code server.getPort()},
     * it's available immediately during {@code SERVER_STARTING}, before the
     * network handler has bound a socket (where {@code getPort()} can still
     * return {@code -1}).
     *
     * @return the configured port, or {@code -1} if it can't be determined.
     */
    private int readGamePortFromProperties(Path serverDirectory) {
        Path propsPath = serverDirectory.resolve("server.properties");
        if (!Files.exists(propsPath)) {
            LOGGER.warn("server.properties not found at {}", propsPath);
            return -1;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(propsPath)) {
            props.load(r);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}: {}", propsPath, e.getMessage());
            return -1;
        }
        String raw = props.getProperty("server-port");
        if (raw == null || raw.isBlank()) {
            LOGGER.warn("server-port not set in {}", propsPath);
            return -1;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            if (port > 0 && port < 65536) {
                LOGGER.info("Read server-port={} from {}", port, propsPath);
                return port;
            }
            LOGGER.warn("server-port={} in {} is out of range", port, propsPath);
            return -1;
        } catch (NumberFormatException e) {
            LOGGER.warn("server-port='{}' in {} is not an integer", raw, propsPath);
            return -1;
        }
    }

    // ── Config ───────────────────────────────────────────────────────────────

    /**
     * Read {@code httpPort} from {@code config/conduit.properties}. If absent or
     * unparsable, fall back to {@code gamePort + DEFAULT_PORT_OFFSET}. The
     * properties file is created (with a comment) on first run so operators
     * discover the option without reading docs.
     */
    private int resolveHttpPort(Path configDir, int gamePort) {
        Path propsPath = configDir.resolve("conduit.properties");
        Properties props = new Properties();

        if (Files.exists(propsPath)) {
            try (Reader r = Files.newBufferedReader(propsPath)) {
                props.load(r);
            } catch (IOException e) {
                LOGGER.warn("Could not read {}: {}", propsPath, e.getMessage());
            }
        } else {
            writeDefaultProperties(propsPath, gamePort);
        }

        String raw = props.getProperty("httpPort");
        if (raw != null && !raw.isBlank()) {
            try {
                int port = Integer.parseInt(raw.trim());
                if (port > 0 && port < 65536) return port;
                LOGGER.warn("httpPort={} out of range; using default ({}+{})",
                        port, gamePort, DEFAULT_PORT_OFFSET);
            } catch (NumberFormatException e) {
                LOGGER.warn("httpPort='{}' is not an integer; using default ({}+{})",
                        raw, gamePort, DEFAULT_PORT_OFFSET);
            }
        }
        return gamePort + DEFAULT_PORT_OFFSET;
    }

    private void writeDefaultProperties(Path propsPath, int gamePort) {
        try {
            Files.createDirectories(propsPath.getParent());
            try (Writer w = Files.newBufferedWriter(propsPath)) {
                // Properties.store would work, but a hand-written header reads
                // better when an operator opens it cold.
                w.write("# Conduit configuration\n");
                w.write("# Port for the HTTP manifest endpoint. Empty = auto (game port + 1 = "
                        + (gamePort + DEFAULT_PORT_OFFSET) + ").\n");
                w.write("httpPort=\n");
            }
            LOGGER.info("Wrote default config to {}", propsPath);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", propsPath, e.getMessage());
        }
    }
}
