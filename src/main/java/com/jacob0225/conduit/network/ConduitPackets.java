package com.jacob0225.conduit.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers Conduit's packet types with Fabric's PayloadTypeRegistry.
 *
 * In 26.1, every CustomPacketPayload type must be registered on BOTH sides
 * via PayloadTypeRegistry before any listener or sender is used.
 *
 * Call ConduitPackets.registerCommon() from the common (server) initializer.
 */
public final class ConduitPackets {

    private ConduitPackets() {}

    /**
     * Register the S2C manifest sync payload type.
     * Must be called in the common (main) initializer on BOTH server and client.
     */
    public static void registerCommon() {
        // clientboundPlay = server sends, client receives (renamed in 26.1)
        PayloadTypeRegistry.clientboundPlay().register(
                ManifestPayload.TYPE,
                ManifestPayload.CODEC
        );
    }
}
