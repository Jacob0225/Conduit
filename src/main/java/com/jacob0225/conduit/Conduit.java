package com.jacob0225.conduit;

import com.jacob0225.conduit.manifest.ServerManifestProvider;
import com.jacob0225.conduit.network.ConduitPackets;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Server-side (and common) entrypoint for Conduit.
 *
 * 26.1 networking pattern:
 *   1. Register payload types in the common init (PayloadTypeRegistry).
 *   2. On player JOIN, send the ManifestPayload if the client supports it.
 *   3. ServerPlayNetworking.send(player, payload) — no more FriendlyByteBuf wrangling.
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

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ensureManifestLoaded(server);

            // canSend checks that the client registered the same payload type
            // (i.e. also called PayloadTypeRegistry.playS2C().register for our type)
            if (ServerPlayNetworking.canSend(handler, ManifestPayload.TYPE)) {
                sendManifest(handler.player, server);
            } else {
                LOGGER.debug("Client {} does not have Conduit; skipping manifest sync.",
                        handler.player.getName().getString());
            }
        });

        LOGGER.info("Conduit server-side ready.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void ensureManifestLoaded(MinecraftServer server) {
        if (manifestProvider == null) {
            Path configDir = server.getServerDirectory().resolve("config");
            manifestProvider = new ServerManifestProvider(configDir);
            manifestProvider.load();
        }
    }

    private void sendManifest(net.minecraft.server.level.ServerPlayer player, MinecraftServer server) {
        try {
            ManifestPayload payload = manifestProvider.getPayload();
            // New 26.1 API: just pass the payload record directly
            ServerPlayNetworking.send(player, payload);
            LOGGER.debug("Sent manifest to {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send Conduit manifest to {}: {}",
                    player.getName().getString(), e.getMessage());
        }
    }
}
