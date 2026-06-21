package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.network.ClientManifestHandler;
import com.jacob0225.conduit.network.ConduitPackets;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side entrypoint for Conduit.
 *
 * NOTE: ConduitPackets.registerCommon() is normally already called for us by
 * the 'main' entrypoint, since Fabric runs ModInitializer on both the client
 * and the dedicated server and the PayloadTypeRegistry is global. We call it
 * again here for safety against entrypoint-ordering surprises; the method is
 * idempotent, so the duplicate call is a no-op (see ConduitPackets).
 */
public class ConduitClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Conduit client initializing");

        // Register payload types (idempotent — already done by the main entrypoint).
        ConduitPackets.registerCommon();

        // Register the packet receiver
        ClientManifestHandler.register();

        LOGGER.info("Conduit client ready — listening for manifest sync packets");
    }
}
