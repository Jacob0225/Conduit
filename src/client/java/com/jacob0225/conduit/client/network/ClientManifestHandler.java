package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.download.InstalledModIndex;
import com.jacob0225.conduit.client.download.ManifestVerifier;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Receives the ManifestPayload from the server during the <b>configuration
 * phase</b> — before the server sends its registries.
 *
 * <p>If all required mods are already installed → do nothing; the handshake
 * proceeds normally and registries will decode fine.
 *
 * <p>If mods are missing/outdated → capture the server address from the
 * listener's {@link ServerData}, disconnect cleanly, and show
 * {@link ModReviewScreen} as a standalone install screen. Installing mods
 * mid-connection is impossible (Fabric loads them at game start), so the
 * connection must end. Once install completes the screen reconnects to the
 * captured address automatically.
 */
public class ClientManifestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    /**
     * The address of the server we were connecting to when the manifest arrived.
     * Captured before disconnect so {@link ModReviewScreen} can reconnect later.
     */
    private static volatile ServerAddress pendingServerAddress;
    private static volatile String pendingServerName;

    public static void register() {
        ClientConfigurationNetworking.registerGlobalReceiver(
                ManifestPayload.TYPE,
                ClientManifestHandler::onManifestReceived
        );
    }

    /** @return the server address captured from the last manifest, if any. */
    public static Optional<ServerAddress> getPendingServerAddress() {
        return Optional.ofNullable(pendingServerAddress);
    }

    /** @return a display name for the server captured from the last manifest, if any. */
    public static Optional<String> getPendingServerName() {
        return Optional.ofNullable(pendingServerName);
    }

    /** Clear captured state (called after a successful reconnect attempt). */
    public static void clearPending() {
        pendingServerAddress = null;
        pendingServerName = null;
    }

    private static void onManifestReceived(
            ManifestPayload payload,
            ClientConfigurationNetworking.Context context
    ) {
        LOGGER.info("Received Conduit manifest v{} from '{}': {} mod(s)",
                payload.manifestVersion(), payload.serverName(), payload.mods().size());

        InstalledModIndex index   = new InstalledModIndex();
        ManifestVerifier verifier = new ManifestVerifier(index);
        ManifestVerifier.DiffResult diff = verifier.diff(payload);

        if (!diff.hasWork()) {
            LOGGER.info("All required mods already installed — proceeding to join.");
            return;
        }

        LOGGER.info("Mods needed: {} missing, {} outdated — disconnecting to install.",
                diff.missing().size(), diff.outdated().size());

        // Capture the address BEFORE we tear the connection down. The config-phase
        // packet listener's serverData holds the original IP the user typed; we
        // expose it via MixinClientConfigurationConnectionAccess. If that's
        // unavailable (e.g. a server transfer with no ServerData), reconnect
        // won't be possible and the user must rejoin manually.
        ServerAddress captured = captureAddress(context.packetListener());
        if (captured != null) {
            pendingServerAddress = captured;
            pendingServerName = payload.serverName();
            LOGGER.info("Captured server address for reconnect: {}", captured);
        } else {
            LOGGER.warn("Could not capture server address; auto-reconnect will be unavailable.");
        }

        final ManifestVerifier.DiffResult diffFinal = diff;
        Minecraft client = context.client();
        client.execute(() -> {
            // disconnect(Screen, boolean): the Screen becomes the active screen
            // after the connection is torn down. The boolean is 'transfers' —
            // false because we want a full drop to the install screen, not a
            // transfer-style reconnect. The disconnect reason text is shown on
            // the screen itself, so we pass our install screen directly.
            client.disconnect(new ModReviewScreen(payload, diffFinal), false);
        });
    }

    /**
     * Pull the user-typed address off the config-phase listener via the mixin
     * accessor. Returns null if the listener has no ServerData (e.g. a server
     * transfer rather than a fresh connect).
     */
    private static ServerAddress captureAddress(
            net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl listener
    ) {
        try {
            // The accessor mixin is on ClientCommonPacketListenerImpl, the
            // abstract base of the config listener; cast through it to reach
            // the protected serverData field.
            var common = (com.jacob0225.conduit.client.mixin.MixinClientConfigurationConnectionAccess)
                    listener;
            ServerData data = common.conduit_getServerData();
            if (data == null) return null;
            return ServerAddress.parseString(data.ip);
        } catch (Exception e) {
            LOGGER.warn("Could not parse server address for reconnect: {}", e.getMessage());
            return null;
        }
    }
}
