package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.network.ClientManifestHandler;
import com.jacob0225.conduit.network.ConduitPackets;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side entrypoint for Conduit.
 *
 * NOTE: ConduitPackets.registerCommon() must be called here as well as in the
 * server entrypoint. Fabric requires that PayloadTypeRegistry registrations
 * happen on both sides before the play phase begins — if the client hasn't
 * registered the type, canSend() returns false and the server won't send.
 */
public class ConduitClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Conduit client initializing");

        // Register payload types on the client side (mirrors server's registerCommon call)
        ConduitPackets.registerCommon();

        // Register the packet receiver
        ClientManifestHandler.register();

        LOGGER.info("Conduit client ready — listening for manifest sync packets");
    }
}
