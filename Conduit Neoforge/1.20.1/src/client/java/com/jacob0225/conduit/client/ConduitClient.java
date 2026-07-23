package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.network.PortDiscovery;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

public final class ConduitClient {
    private static SimpleChannel channel;
    private ConduitClient() {}
    public static void bindChannel(SimpleChannel value) { channel = value; }
    public static void initialize() {
        ClientConfig.load();
        verifyInstallRecords();
        MinecraftForge.EVENT_BUS.addListener(ConduitClient::onClientTick);
    }
    public static void handleAdvertisement(PortDiscovery.Advertisement advertisement,
                                           Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.setPacketHandled(true);
        ConduitJoinInterceptor.validateAdvertisement(advertisement.endpoint()).whenComplete((status, error) -> {
            byte response = error == null ? status : PortDiscovery.ENDPOINT_UNREACHABLE;
            channel.reply(new PortDiscovery.Response(response), context);
            if (response == PortDiscovery.NEEDS_SETUP) {
                context.getNetworkManager().channel().closeFuture().addListener(future ->
                        ConduitJoinInterceptor.onLoginDisconnect());
            }
        });
    }
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ConduitJoinInterceptor.onClientTick();
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
