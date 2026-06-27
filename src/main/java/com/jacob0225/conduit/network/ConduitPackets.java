package com.jacob0225.conduit.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers Conduit's packet types with Fabric's PayloadTypeRegistry.
 *
 * <p>The manifest is delivered during the <b>configuration phase</b>, not the
 * play phase. This is essential: the server sends its registries (custom
 * blocks, entities, etc. contributed by the server's mods) as part of the
 * configuration handshake, which runs <i>before</i> {@code ServerPlayConnection
 * Events.JOIN}. If we waited for JOIN — as the original code did — the client
 * would already have received registry packets it cannot decode because the
 * required mods are not installed yet, producing a "registry mismatch" disconnect.
 *
 * <p>Sending during configuration lets the client learn what it needs, install
 * it, and only then let the registry exchange proceed.
 *
 * <p>Call {@link #registerCommon()} from the common (server) initializer.
 */
public final class ConduitPackets {

    private ConduitPackets() {}

    /**
     * Register the C2S manifest sync payload type on the configuration channel.
     *
     * <p>The PayloadTypeRegistry is global (shared by both sides), and Fabric's
     * 'main' entrypoint runs on BOTH the dedicated server and the client.
     * Registering there once is sufficient for encode (server) AND decode
     * (client) — so this method is NOT called from the client entrypoint.
     *
     * <p>A guard makes this idempotent in case it is ever invoked more than once.
     */
    private static volatile boolean registered = false;

    public static void registerCommon() {
        if (registered) return;
        registered = true;

        // clientboundConfiguration = server sends during config phase, client
        // receives during config phase. Registered on the configuration channel
        // (not play) so the packet arrives BEFORE the server's registry sync.
        PayloadTypeRegistry.clientboundConfiguration().register(
                ManifestPayload.TYPE,
                ManifestPayload.CODEC
        );
    }
}
