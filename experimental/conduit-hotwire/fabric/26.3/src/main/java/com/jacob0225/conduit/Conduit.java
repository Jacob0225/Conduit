package com.jacob0225.conduit;

import com.jacob0225.conduit.config.ServerConfig;
import com.jacob0225.conduit.network.PortDiscovery;
import com.jacob0225.conduit.server.ConduitServerRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Server-side (and common) entrypoint for Conduit.
 *
 * <p><b>Architecture.</b> The manifest is delivered over a separate HTTP
 * endpoint, while its configured port is advertised through a Minecraft login
 * query. This is deliberate:
 * Minecraft's registry sync runs during the configuration phase, so any manifest
 * packet sent inside the game connection would race (and lose to) the registry
 * packets. The login query pauses before registry synchronization, allowing a
 * clean disconnect and install when the client needs changes.
 *
 * <p>The client validates the HTTP manifest while login is paused, then tells
 * the server whether login may continue. Missing mods cause a clean disconnect
 * before registry synchronization and the existing install/restart flow runs.
 *
 * <p>Lifecycle: the manifest is (re)loaded and the HTTP server (re)started on
 * every {@code SERVER_STARTING}, and both are torn down on
 * {@code SERVER_STOPPED}. Config lives at {@code config/conduit/conduit-server.properties}.
 */
public class Conduit implements ModInitializer {

    public static final String MOD_ID = "conduit";
    public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

    /**
     * Default manifest port. The actual configured value is advertised to each
     * connecting Conduit client during Minecraft login.
     */
    public static final int DEFAULT_HTTP_PORT = 25566;

    private final ConduitServerRuntime serverRuntime = new ConduitServerRuntime();

    @Override
    public void onInitialize() {
        LOGGER.info("Conduit initializing (HTTP manifest mode)");

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            int port = serverRuntime.advertisedHttpPort();
            if (port > 0) {
                sender.sendPacket(PortDiscovery.CHANNEL, PortDiscovery.advertisement(
                        port, serverRuntime.advertisedPublicBaseUrl()));
            }
        });
        ServerLoginNetworking.registerGlobalReceiver(PortDiscovery.CHANNEL,
                (server, handler, understood, buf, synchronizer, responseSender) -> {
                    if (!understood || buf == null || !buf.isReadable()) {
                        LOGGER.info("Client does not support Conduit discovery; allowing optional login");
                        return;
                    }
                    byte status = buf.readByte();
                    if (status == PortDiscovery.READY) {
                        return;
                    }
                    if (status == PortDiscovery.ENDPOINT_UNREACHABLE) {
                        LOGGER.warn("Client could not reach the optional Conduit manifest endpoint; allowing login");
                    } else {
                        handler.disconnect(Component.literal(
                                "Conduit is preparing the required mods. Reconnect after setup finishes."));
                    }
                });

        LOGGER.info("Conduit server-side ready. HTTP port discovery is active during login.");
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
