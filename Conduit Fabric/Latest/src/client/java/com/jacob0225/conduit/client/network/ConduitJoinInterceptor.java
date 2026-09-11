package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.http.ManifestHttpClient;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
import com.jacob0225.conduit.client.ui.ModSwitchScreen;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import com.jacob0225.conduit.network.PortDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/** Coordinates manifest validation while the Minecraft login query is paused. */
public final class ConduitJoinInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Join");
    private static volatile Attempt attempt;
    private static volatile Runnable afterDisconnect;
    private static volatile boolean loginDisconnected;

    private ConduitJoinInterceptor() {}

    public static void rememberConnection(Screen parent, ServerAddress address, ServerData serverData) {
        attempt = new Attempt(parent, address, serverData);
        afterDisconnect = null;
        loginDisconnected = false;
    }

    public static CompletableFuture<Byte> validateAdvertisement(PortDiscovery.Endpoint endpoint) {
        Attempt current = attempt;
        if (current == null) {
            LOGGER.warn("Received a Conduit port advertisement without a remembered join attempt");
            return CompletableFuture.completedFuture(PortDiscovery.ENDPOINT_UNREACHABLE);
        }

        String host = current.address().getHost();
        int gamePort = current.address().getPort();
        int httpPort = endpoint.port();
        LOGGER.info("Server advertised Conduit HTTP port {} during login to {}:{}",
                httpPort, host, gamePort);

        return ManifestHttpClient.fetch(host, httpPort, endpoint.publicBaseUrl()).handle((manifestOpt, error) -> {
            if (error != null || manifestOpt.isEmpty()) {
                LOGGER.warn("The advertised Conduit endpoint {}:{} could not be reached", host, httpPort);
                return PortDiscovery.ENDPOINT_UNREACHABLE;
            }

            ManifestPayload payload = manifestOpt.get();
            ServerInstallRecord record = ServerInstallRecord.forServer(host, gamePort);
            if (record.isSatisfied(payload)) {
                ModStashManager.ReconcileResult result = new ModStashManager()
                        .reconcile(record.key(), record.ownedJarFilenames());
                if (!result.changed()) {
                    LOGGER.info("Manifest satisfied; allowing the existing Minecraft login to finish");
                    return PortDiscovery.READY;
                }
                afterDisconnect = () -> Minecraft.getInstance().setScreenAndShow(
                        new ModSwitchScreen(payload.serverName(), current.parent()));
                return PortDiscovery.NEEDS_SETUP;
            }

            int required = (int) payload.mods().stream().filter(ModEntry::required).count();
            LOGGER.info("Manifest requires setup ({} required mod(s)); ending login before registry sync",
                    required);
            afterDisconnect = () -> Minecraft.getInstance().setScreenAndShow(
                    new ModReviewScreen(payload, current.address(), current.serverData(), current.parent()));
            return PortDiscovery.NEEDS_SETUP;
        });
    }

    public static void onLoginDisconnect() {
        loginDisconnected = true;
    }

    /** Runs one tick later so vanilla's disconnect screen cannot overwrite Conduit's setup UI. */
    public static void onClientTick() {
        if (!loginDisconnected) return;
        loginDisconnected = false;
        Runnable action = afterDisconnect;
        afterDisconnect = null;
        if (action != null) action.run();
    }

    /** Retained for existing review/switch screen call sites; login is revalidated on every connection. */
    public static void bypassNextCheck() {}

    public static boolean shouldBypass() { return false; }

    static void resetState() {
        attempt = null;
        afterDisconnect = null;
        loginDisconnected = false;
    }

    private record Attempt(Screen parent, ServerAddress address, ServerData serverData) {}
}
