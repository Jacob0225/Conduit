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
     *
     * The PayloadTypeRegistry is global (shared by both sides), and Fabric's
     * 'main' entrypoint runs on BOTH the dedicated server and the client.
     * Registering there once is sufficient for encode (server) AND decode
     * (client) — so this method is NOT called from the client entrypoint.
     *
     * A guard makes this idempotent in case it is ever invoked more than once.
     */
    private static volatile boolean registered = false;

    public static void registerCommon() {
        if (registered) return;
        registered = true;

        // clientboundPlay = server sends, client receives (renamed in 26.1)
        PayloadTypeRegistry.clientboundPlay().register(
                ManifestPayload.TYPE,
                ManifestPayload.CODEC
        );
    }
}
