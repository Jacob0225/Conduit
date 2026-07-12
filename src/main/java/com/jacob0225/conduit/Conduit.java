package com.jacob0225.conduit;

import com.jacob0225.conduit.config.ServerConfig;
import com.jacob0225.conduit.server.ConduitServerRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
 * {@code SERVER_STOPPED}. Config lives at {@code config/conduit/server.properties}.
 */
public class Conduit implements ModInitializer {

    public static final String MOD_ID = "conduit";
    public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

    /**
     * Default manifest port. A fixed value (25566) is used so operators only
     * need to forward one well-known port regardless of their game port — this
     * is the value the client probes first too. Override via {@code httpPort}
     * in {@code config/conduit/server.properties}.
     */
    public static final int DEFAULT_HTTP_PORT = 25566;

    private final ConduitServerRuntime serverRuntime = new ConduitServerRuntime();

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
        serverRuntime.start(server.getServerDirectory(), server.getServerVersion());
        LOGGER.info("════════ CONDUIT SERVER STARTED ════════");
    }

    private void onServerStopped(MinecraftServer server) {
        serverRuntime.stop();
    }

    // ── Config ───────────────────────────────────────────────────────────────

}
