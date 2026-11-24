package com.jacob0225.conduit;

import com.jacob0225.conduit.client.ConduitClient;
import com.jacob0225.conduit.network.PortDiscovery;
import com.jacob0225.conduit.server.ConduitServerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.network.GatherLoginConfigurationTasksEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.network.config.ConfigurationTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

@Mod(Conduit.MOD_ID)
public class Conduit {
    public static final String MOD_ID = "conduit";
    public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");
    public static final int DEFAULT_HTTP_PORT = 25566;
    private final ConduitServerRuntime serverRuntime = new ConduitServerRuntime();
    private final SimpleChannel channel;

    public Conduit(FMLJavaModLoadingContext context) {
        // Optional both ways: clients and servers without Conduit still connect.
        channel = ChannelBuilder.named(ResourceLocation.fromNamespaceAndPath(MOD_ID, "port_discovery"))
                .networkProtocolVersion(1)
                .optional()
                .simpleChannel();

        channel.messageBuilder(PortDiscovery.Advertisement.class, NetworkDirection.CONFIGURATION_TO_CLIENT)
                .encoder(PortDiscovery.Advertisement::encode)
                .decoder(PortDiscovery.Advertisement::decode)
                .consumerNetworkThread((advertisement, ctx) -> {
                    ctx.setPacketHandled(true);
                    if (FMLEnvironment.dist == Dist.CLIENT) ConduitClient.handleAdvertisement(advertisement, ctx);
                })
                .add();

        channel.messageBuilder(PortDiscovery.Response.class, NetworkDirection.CONFIGURATION_TO_SERVER)
                .encoder(PortDiscovery.Response::encode)
                .decoder(PortDiscovery.Response::decode)
                .consumerNetworkThread((response, ctx) -> {
                    ctx.setPacketHandled(true);
                    if (response.status() == PortDiscovery.NEEDS_SETUP) {
                        ctx.getConnection().disconnect(Component.literal(
                                "Conduit is preparing the required mods. Reconnect after setup finishes."));
                        return;
                    }
                    if (response.status() == PortDiscovery.ENDPOINT_UNREACHABLE) {
                        LOGGER.warn("Client could not reach the optional Conduit endpoint; allowing login");
                    }
                    NetworkContext.get(ctx.getConnection()).finishTask(PortDiscovery.TASK_TYPE);
                })
                .add();

        if (FMLEnvironment.dist == Dist.CLIENT) ConduitClient.bindChannel(channel);
        MinecraftForge.EVENT_BUS.addListener(this::onGatherTasks);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        if (FMLEnvironment.dist == Dist.CLIENT) ConduitClient.initialize(context);
        LOGGER.info("Conduit Forge 1.1.0 initialized");
    }

    private void onGatherTasks(GatherLoginConfigurationTasksEvent event) {
        event.addTask(new ConfigurationTask() {
            @Override
            public void start(ConfigurationTaskContext ctx) {
                int port = serverRuntime.advertisedHttpPort();
                // Tasks run in order, so Forge's channel exchange has already told us
                // whether this client has Conduit. Skip quietly when it does not.
                if (port <= 0 || !channel.isRemotePresent(ctx.getConnection())) {
                    ctx.finish(PortDiscovery.TASK_TYPE);
                    return;
                }
                channel.send(new PortDiscovery.Advertisement(port, serverRuntime.advertisedPublicBaseUrl()),
                        ctx.getConnection());
            }

            @Override
            public void start(Consumer<Packet<?>> send) {
                throw new IllegalStateException("Forge calls start(ConfigurationTaskContext)");
            }

            @Override
            public Type type() { return PortDiscovery.TASK_TYPE; }
        });
    }

    private void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        serverRuntime.start(server.getServerDirectory(), server.getServerVersion());
    }
    private void onServerStopped(ServerStoppedEvent event) { serverRuntime.stop(); }
}
