package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.download.InstalledModIndex;
import com.jacob0225.conduit.client.download.ManifestVerifier;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives the ManifestPayload from the server and dispatches to the review UI.
 *
 * 26.1 pattern: ClientPlayNetworking.registerGlobalReceiver takes
 * (CustomPacketPayload.Type<T>, ClientPlayNetworking.PlayPayloadHandler<T>).
 * The handler receives the already-decoded payload object — no raw buf handling.
 *
 * Security: ManifestPayload.CODEC already validated every field during decode
 * (via ModEntry's constructor → InputValidator). By the time this handler runs,
 * the payload is already clean or the packet was discarded.
 */
public class ClientManifestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                ManifestPayload.TYPE,
                ClientManifestHandler::onManifestReceived
        );
    }

    /**
     * Called on the network thread. Switch to render thread before touching
     * any Minecraft state.
     */
    private static void onManifestReceived(
            ManifestPayload payload,
            ClientPlayNetworking.Context context
    ) {
        LOGGER.info("Received Conduit manifest v{} from '{}': {} mod(s)",
                payload.manifestVersion(), payload.serverName(), payload.mods().size());

        // Safe to do on the network thread — InstalledModIndex only reads FabricLoader state
        InstalledModIndex index      = new InstalledModIndex();
        ManifestVerifier verifier    = new ManifestVerifier(index);
        ManifestVerifier.DiffResult diff = verifier.diff(payload);

        if (!diff.hasWork()) {
            LOGGER.info("All required mods already installed — no downloads needed.");
            return;
        }

        LOGGER.info("Mods needed: {} missing, {} outdated",
                diff.missing().size(), diff.outdated().size());

        // Must open the screen on the render thread
        context.client().execute(() ->
                context.client().setScreen(new ModReviewScreen(payload, diff))
        );
    }
}
