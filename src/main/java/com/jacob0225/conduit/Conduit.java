package com.jacob0225.conduit;

import com.jacob0225.conduit.manifest.ServerManifestProvider;
import com.jacob0225.conduit.network.ConduitPackets;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Server-side (and common) entrypoint for Conduit.
 *
 * <p>Networking pattern (configuration phase — required so the manifest arrives
 * <b>before</b> the server's registry sync):
 *   <ol>
 *     <li>Register the payload type in the common init (PayloadTypeRegistry).
 *     <li>On {@code CONFIGURE}, if the client supports the payload, send it.
 *         This is the earliest point a custom payload can be sent and it fires
 *         before any registry packets, so a client missing the required mods can
 *         install them and reconnect instead of hitting a registry error.
 *   </ol>
 *
 * <p>The manifest is (re)loaded when each server starts ({@code SERVER_STARTING})
 * rather than lazily on first connect, so the config file is generated up front
 * and reflects edits on every restart.
 */
public class Conduit implements ModInitializer {

    public static final String MOD_ID = "conduit";
    public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

    private ServerManifestProvider manifestProvider;

    @Override
    public void onInitialize() {
        LOGGER.info("Conduit initializing");

        // Must register payload types before any send/receive calls on both sides
        ConduitPackets.registerCommon();

        // (Re)load the manifest as each server starts. Doing it here — rather than
        // lazily on first player join — guarantees config/conduit/manifest.json is
        // created/refreshed on every startup, including before anyone connects.
        ServerLifecycleEvents.SERVER_STARTING.register(this::ensureManifestLoaded);

        // Send the manifest during the configuration phase, before the server's
        // registry sync. A client that lacks the required mods can then install
        // them and reconnect, instead of crashing on undecodable registry data.
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            // canSend checks that the client registered the same payload type
            // (i.e. also has Conduit installed and registered the type).
            if (ServerConfigurationNetworking.canSend(handler, ManifestPayload.TYPE)) {
                sendManifest(handler, server);
            } else {
                // Client doesn't have Conduit at all — log and let the vanilla
                // handshake continue. If the server's mods register custom
                // content, vanilla's registry check will disconnect this client
                // with a clear "mismatched registry" message.
                LOGGER.debug("Client lacks Conduit; skipping manifest sync.");
            }
        });

        LOGGER.info("Conduit server-side ready.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void ensureManifestLoaded(MinecraftServer server) {
        // Refresh on every server start so edits to manifest.json are picked up.
        Path configDir = server.getServerDirectory().resolve("config");
        // Pass the running MC version so the resolver queries the right game version
        String mcVersion = server.getServerVersion();
        manifestProvider = new ServerManifestProvider(configDir, mcVersion);
        manifestProvider.load();
    }

    private void sendManifest(ServerConfigurationPacketListenerImpl handler, MinecraftServer server) {
        try {
            ManifestPayload payload = manifestProvider.getPayload();
            // Configuration-phase send: payload is delivered before registry sync.
            ServerConfigurationNetworking.send(handler, payload);
            LOGGER.debug("Sent Conduit manifest during configuration phase.");
        } catch (Exception e) {
            LOGGER.error("Failed to send Conduit manifest: {}", e.getMessage());
        }
    }
}
