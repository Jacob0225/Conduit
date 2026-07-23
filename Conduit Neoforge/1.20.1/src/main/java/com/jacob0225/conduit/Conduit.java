package com.jacob0225.conduit;

import com.jacob0225.conduit.client.ConduitClient;
import com.jacob0225.conduit.network.PortDiscovery;
import com.jacob0225.conduit.server.ConduitServerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Mod(Conduit.MOD_ID)
public class Conduit {
    public static final String MOD_ID = "conduit";
    public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");
    public static final int DEFAULT_HTTP_PORT = 25566;
    private final ConduitServerRuntime serverRuntime = new ConduitServerRuntime();
    private final SimpleChannel channel;

    public Conduit() {
        channel = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(MOD_ID, "login"))
                .networkProtocolVersion(() -> "1")
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
                .serverAcceptedVersions(NetworkRegistry.acceptMissingOr("1"))
                .simpleChannel();

        channel.messageBuilder(PortDiscovery.Advertisement.class, 0, NetworkDirection.LOGIN_TO_CLIENT)
                .loginIndex(PortDiscovery.Advertisement::loginIndex, PortDiscovery.Advertisement::loginIndex)
                .encoder(PortDiscovery.Advertisement::encode)
                .decoder(PortDiscovery.Advertisement::decode)
                .buildLoginPacketList(local -> {
                    int port = serverRuntime.advertisedHttpPort();
                    if (port <= 0) return List.of();
                    return List.of(Pair.of("Conduit manifest endpoint",
                            new PortDiscovery.Advertisement(port, serverRuntime.advertisedPublicBaseUrl())));
                })
                .consumerNetworkThread(ConduitClient::handleAdvertisement)
                .add();

        channel.messageBuilder(PortDiscovery.Response.class, 1, NetworkDirection.LOGIN_TO_SERVER)
                .loginIndex(PortDiscovery.Response::loginIndex, PortDiscovery.Response::loginIndex)
                .encoder(PortDiscovery.Response::encode)
                .decoder(PortDiscovery.Response::decode)
                .consumerNetworkThread((response, contextSupplier) -> {
                    var context = contextSupplier.get();
                    if (response.status() == PortDiscovery.NEEDS_SETUP) {
                        context.getNetworkManager().disconnect(Component.literal(
                                "Conduit is preparing the required mods. Reconnect after setup finishes."));
                    } else if (response.status() == PortDiscovery.ENDPOINT_UNREACHABLE) {
                        LOGGER.warn("Client could not reach the optional Conduit endpoint; allowing login");
                    }
                    context.setPacketHandled(true);
                })
                .add();

        ConduitClient.bindChannel(channel);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ConduitClient::initialize);
        LOGGER.info("Conduit 1.20.1 Forge-compatible 1.1.0 initialized");
    }

    private void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        serverRuntime.start(server.getServerDirectory().toPath(), server.getServerVersion());
    }
    private void onServerStopped(ServerStoppedEvent event) { serverRuntime.stop(); }
}
