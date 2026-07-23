package com.jacob0225.conduit.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "conduit-velocity",
        name = "Conduit Velocity",
        version = "1.1.0",
        description = "Velocity login bridge for the optional Conduit client mod",
        authors = {"jacob0225"}
)
public final class ConduitVelocityPlugin {
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("conduit:http_port");

    private final Logger logger;
    private final Path dataDirectory;
    private final Map<String, Byte> pendingStatuses = new ConcurrentHashMap<>();
    private volatile VelocityConfig config;

    @Inject
    public ConduitVelocityPlugin(Logger logger, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        config = VelocityConfig.load(dataDirectory, logger);
        logger.info("Conduit Velocity initialized; optional login discovery is {}",
                config.enabled() ? "enabled" : "disabled");
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        VelocityConfig current = config;
        if (current == null || !current.enabled()) return;
        if (!(event.getConnection() instanceof LoginPhaseConnection connection)) {
            logger.warn("Connection for {} does not support Velocity login plugin messages", event.getUsername());
            return;
        }

        String username = key(event.getUsername());
        String joinedHost = connection.getVirtualHost()
                .map(InetSocketAddress::getHostString)
                .orElse("");
        VelocityConfig.Endpoint endpoint = current.endpointFor(joinedHost);
        byte[] advertisement = ConduitProtocol.advertisement(
                endpoint.port(), endpoint.publicBaseUrl());

        pendingStatuses.remove(username);
        connection.sendLoginPluginMessage(CHANNEL, advertisement, response -> {
            byte status = ConduitProtocol.responseStatus(response);
            if (status == ConduitProtocol.NEEDS_SETUP) {
                pendingStatuses.put(username, status);
                logger.info("Conduit setup requested for {} joining {}", event.getUsername(), joinedHost);
            } else {
                pendingStatuses.remove(username);
                if (status == ConduitProtocol.ENDPOINT_UNREACHABLE) {
                    logger.warn("{} could not reach the optional Conduit endpoint for {}; allowing login",
                            event.getUsername(), joinedHost);
                }
            }
        });
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Byte status = pendingStatuses.remove(key(event.getPlayer().getUsername()));
        if (status != null && status == ConduitProtocol.NEEDS_SETUP) {
            event.setResult(ResultedEvent.ComponentResult.denied(Component.text(
                    "Conduit is preparing the recommended mods. Reconnect after setup finishes.")));
        }
    }

    /** Answer the backend mod after the proxy already performed discovery with the real client. */
    @Subscribe
    public void onBackendLoginQuery(ServerLoginPluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        VelocityConfig current = config;
        if (current == null || !current.enabled()) return;
        event.setResult(ServerLoginPluginMessageEvent.ResponseResult.reply(
                new byte[]{ConduitProtocol.READY}));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        pendingStatuses.remove(key(event.getPlayer().getUsername()));
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
