package com.jacob0225.conduit.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client-side entrypoint for Conduit.
 *
 * <p>In HTTP mode there is almost nothing to do at init time: the join
 * interception is handled entirely by {@code MixinConnectScreen} (which Mixin
 * registers automatically), and the manifest is fetched on demand by
 * {@link com.jacob0225.conduit.client.http.ManifestHttpClient}. No packet
 * types or receivers are registered — the manifest never travels over the
 * Minecraft protocol.
 *
 * <p>This class exists primarily for the log line and as a place to hang any
 * future client-side initialization.
 */
public class ConduitClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Conduit client ready — join interception active (HTTP manifest mode)");
    }
}
