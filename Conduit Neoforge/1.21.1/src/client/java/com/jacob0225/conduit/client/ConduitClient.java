package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.network.PortDiscovery;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConduitClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");
    private ConduitClient() {}
    public static void initialize() {
        ClientConfig.load();
        verifyInstallRecords();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ConduitJoinInterceptor.onClientTick());
        LOGGER.info("Conduit NeoForge client ready");
    }
    public static void handleAdvertisement(PortDiscovery.Advertisement advertisement, IPayloadContext context) {
        ConduitJoinInterceptor.validateAdvertisement(advertisement.endpoint()).whenComplete((status, error) -> {
            byte response = error == null ? status : PortDiscovery.ENDPOINT_UNREACHABLE;
            context.reply(new PortDiscovery.Response(response));
            if (response == PortDiscovery.NEEDS_SETUP) {
                context.connection().channel().closeFuture().addListener(future ->
                        ConduitJoinInterceptor.onLoginDisconnect());
            }
        });
    }
    private static void verifyInstallRecords() {
        Path gameDir = PlatformPaths.gameDir();
        Path modsDir = gameDir.resolve("mods");
        Path stashDir = gameDir.resolve("conduit-stash");
        for (ServerInstallRecord rec : ServerInstallRecord.allRecords()) {
            boolean stale = rec.ownedJarFilenames().stream().anyMatch(fn ->
                    !Files.exists(modsDir.resolve(fn))
                            && !Files.exists(stashDir.resolve(rec.key().stem()).resolve(fn)));
            if (stale) rec.delete();
        }
    }
}
