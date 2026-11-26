package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.http.ManifestHttpClient;
import com.jacob0225.conduit.network.ConduitStatusMarker;
import com.jacob0225.conduit.network.PortDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Checks a server's Conduit manifest before joining. A server running mods with required
 * network channels (Create Connected, for one) rejects a client that lacks them as the join
 * starts, before Conduit's in-login check can run, so the check has to come first.
 *
 * <p>The server-list ping says whether a server runs Conduit and where its manifest is. A server
 * that was never pinged (Direct Connect) is pinged here first. Anything unexpected falls back to a
 * normal join, where the in-login check still applies.
 */
public final class ConduitPreflight {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Preflight");
    private static final long PING_TIMEOUT_MS = 5_000;

    private static boolean bypass;
    private static Ping ping;

    private ConduitPreflight() {}

    /** True if the join was taken over (the caller must cancel it). */
    public static boolean intercept(Screen parent, Minecraft mc, ServerAddress address, ServerData data) {
        if (bypass) {
            bypass = false;
            return false;
        }
        if (data == null || data.isRealm()) return false;
        Optional<PortDiscovery.Endpoint> endpoint = ConduitStatusMarker.endpoint(data.motd);
        if (endpoint.isPresent()) {
            check(parent, mc, address, data, endpoint.get());
            return true;
        }
        // Pinged already: not a Conduit server, or an older one that only checks during login.
        if (data.state() == ServerData.State.SUCCESSFUL) return false;
        if (data.state() != ServerData.State.INITIAL && data.motd != null) return false;
        return startPing(parent, mc, address, data);
    }

    private static boolean startPing(Screen parent, Minecraft mc, ServerAddress address, ServerData data) {
        ServerData probe = new ServerData(data.name, data.ip, data.type());
        ServerStatusPinger pinger = new ServerStatusPinger();
        Ping p = new Ping(parent, address, data, probe, pinger, System.currentTimeMillis() + PING_TIMEOUT_MS);
        try {
            pinger.pingServer(probe, () -> {}, () -> p.finished = true);
        } catch (Exception e) {
            return false; // unknown host etc.: let the normal join report it
        }
        ping = p;
        mc.setScreen(new GenericMessageScreen(Component.literal("Checking the server's mods...")));
        return true;
    }

    /** Drives a pending ping. Called every client tick. */
    public static void onClientTick() {
        Ping p = ping;
        if (p == null) return;
        p.pinger.tick();
        Optional<PortDiscovery.Endpoint> endpoint = ConduitStatusMarker.endpoint(p.probe.motd);
        boolean timedOut = System.currentTimeMillis() > p.deadline;
        if (endpoint.isEmpty() && !p.finished && !timedOut) return;
        ping = null;
        p.pinger.removeAll();
        Minecraft mc = Minecraft.getInstance();
        if (endpoint.isPresent()) {
            check(p.parent, mc, p.address, p.data, endpoint.get());
        } else {
            join(p.parent, mc, p.address, p.data);
        }
    }

    private static void check(Screen parent, Minecraft mc, ServerAddress address, ServerData data,
                              PortDiscovery.Endpoint endpoint) {
        mc.setScreen(new GenericMessageScreen(Component.literal("Checking the server's mods...")));
        LOGGER.info("Checking Conduit manifest of {}:{} before joining", address.getHost(), address.getPort());
        ManifestHttpClient.fetch(address.getHost(), endpoint.port(), endpoint.publicBaseUrl())
                .whenComplete((manifest, error) -> mc.execute(() -> {
                    if (error != null || manifest == null || manifest.isEmpty()) {
                        join(parent, mc, address, data); // endpoint down: the in-login check decides
                        return;
                    }
                    Runnable setup = ConduitJoinInterceptor.setupAction(manifest.get(), parent, address, data);
                    if (setup == null) join(parent, mc, address, data);
                    else setup.run();
                }));
    }

    private static void join(Screen parent, Minecraft mc, ServerAddress address, ServerData data) {
        bypass = true;
        ConnectScreen.startConnecting(parent, mc, address, data, false, null);
    }

    private static final class Ping {
        final Screen parent;
        final ServerAddress address;
        final ServerData data, probe;
        final ServerStatusPinger pinger;
        final long deadline;
        volatile boolean finished;

        Ping(Screen parent, ServerAddress address, ServerData data, ServerData probe, ServerStatusPinger pinger, long deadline) {
            this.parent = parent;
            this.address = address;
            this.data = data;
            this.probe = probe;
            this.pinger = pinger;
            this.deadline = deadline;
        }
    }
}
