package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.ConduitLock;
import com.jacob0225.conduit.client.download.InstalledModIndex;
import com.jacob0225.conduit.client.download.ManifestVerifier;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives the ManifestPayload from the server.
 *
 * If all mods are already installed → do nothing, player joins normally.
 *
 * If mods are missing/outdated → lock the player in place (freeze movement,
 * suppress damage) and open ModReviewScreen as an overlay over the game world.
 * The connection stays alive the entire time. When installation is done the
 * lock is released and the screen is closed — the player is already in.
 */
public class ClientManifestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                ManifestPayload.TYPE,
                ClientManifestHandler::onManifestReceived
        );
    }

    private static void onManifestReceived(
            ManifestPayload payload,
            ClientPlayNetworking.Context context
    ) {
        LOGGER.info("Received Conduit manifest v{} from '{}': {} mod(s)",
                payload.manifestVersion(), payload.serverName(), payload.mods().size());

        InstalledModIndex index   = new InstalledModIndex();
        ManifestVerifier verifier = new ManifestVerifier(index);
        ManifestVerifier.DiffResult diff = verifier.diff(payload);

        if (!diff.hasWork()) {
            LOGGER.info("All required mods already installed — joining normally.");
            return;
        }

        LOGGER.info("Mods needed: {} missing, {} outdated — locking player and opening install screen.",
                diff.missing().size(), diff.outdated().size());

        context.client().execute(() -> {
            ConduitLock.lock();
            context.client().setScreen(new ModReviewScreen(payload, diff));
        });
    }
}
