package com.jacob0225.conduit.server;

import com.jacob0225.conduit.config.ServerConfig;
import com.jacob0225.conduit.http.ConduitHttpServer;
import com.jacob0225.conduit.manifest.ServerManifestProvider;
import com.jacob0225.conduit.network.ConduitStatusMarker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Loader-neutral server lifecycle used by Fabric and Forge adapters. */
public final class ConduitServerRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

    private ConduitHttpServer httpServer;
    private volatile int advertisedHttpPort = -1;
    private volatile String advertisedPublicBaseUrl = "";

    public void start(Path serverDirectory, String minecraftVersion) {
        stop();
        Path configDir = serverDirectory.resolve("config");
        ServerManifestProvider manifestProvider = new ServerManifestProvider(configDir, minecraftVersion);
        manifestProvider.load();

        ServerConfig config = ServerConfig.load(configDir);
        if (!config.enabled()) {
            ConduitStatusMarker.setAvailable(false);
            advertisedHttpPort = -1;
            advertisedPublicBaseUrl = "";
            LOGGER.info("Conduit HTTP manifest endpoint is disabled by config/conduit/conduit-server.properties.");
            return;
        }

        LOGGER.info("Serving {} Conduit mod(s) on {}:{}",
                manifestProvider.getPayload().mods().size(),
                config.bindAddress(),
                config.httpPort());
        httpServer = new ConduitHttpServer(manifestProvider, config);
        httpServer.start();
        advertisedHttpPort = config.httpPort();
        advertisedPublicBaseUrl = config.publicBaseUrl();
        ConduitStatusMarker.setAvailable(true);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        advertisedHttpPort = -1;
        advertisedPublicBaseUrl = "";
        ConduitStatusMarker.setAvailable(false);
    }

    /** Port advertised during Minecraft login, or -1 when the endpoint is unavailable. */
    public int advertisedHttpPort() {
        return advertisedHttpPort;
    }

    public String advertisedPublicBaseUrl() {
        return advertisedPublicBaseUrl;
    }
}
