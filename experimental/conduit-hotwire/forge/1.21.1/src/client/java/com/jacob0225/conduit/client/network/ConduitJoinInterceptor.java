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
        if (current == null) return CompletableFuture.completedFuture(PortDiscovery.ENDPOINT_UNREACHABLE);
        String host = current.address().getHost();
        int gamePort = current.address().getPort();
        int httpPort = endpoint.port();
        LOGGER.info("Server advertised Conduit HTTP port {} during login to {}:{}", httpPort, host, gamePort);
        return ManifestHttpClient.fetch(host, httpPort, endpoint.publicBaseUrl()).handle((manifestOpt, error) -> {
            if (error != null || manifestOpt.isEmpty()) return PortDiscovery.ENDPOINT_UNREACHABLE;
            Runnable setup = setupAction(manifestOpt.get(), current.parent(), current.address(), current.serverData());
            if (setup == null) return PortDiscovery.READY;
            afterDisconnect = setup;
            return PortDiscovery.NEEDS_SETUP;
        });
    }

    /**
     * What to show before joining a server with this manifest, or null if this client already has
     * everything it needs. Shared by the pre-join check and the in-login check.
     */
    public static Runnable setupAction(ManifestPayload payload, Screen parent, ServerAddress address, ServerData serverData) {
        String host = address.getHost();
        int gamePort = address.getPort();
        ServerInstallRecord record = ServerInstallRecord.forServer(host, gamePort);
        if (record.isSatisfied(payload)) {
            ModStashManager.ReconcileResult result = new ModStashManager()
                    .reconcile(record.key(), record.ownedJarFilenames());
            if (!result.changed()) return null;
            return () -> Minecraft.getInstance().setScreen(
                    new ModSwitchScreen(payload.serverName(), parent, address, serverData));
        }
        int required = (int) payload.mods().stream().filter(ModEntry::required).count();
        LOGGER.info("Manifest requires setup ({} required mod(s))", required);
        return () -> Minecraft.getInstance().setScreen(new ModReviewScreen(payload, address, serverData, parent));
    }

    public static void onLoginDisconnect() {
        loginDisconnected = true;
    }

    public static void onClientTick() {
        if (!loginDisconnected) return;
        loginDisconnected = false;
        Runnable action = afterDisconnect;
        afterDisconnect = null;
        if (action != null) action.run();
    }

    public static void bypassNextCheck() {}
    public static boolean shouldBypass() { return false; }
    static void resetState() { attempt = null; afterDisconnect = null; loginDisconnected = false; }
    private record Attempt(Screen parent, ServerAddress address, ServerData serverData) {}
}
