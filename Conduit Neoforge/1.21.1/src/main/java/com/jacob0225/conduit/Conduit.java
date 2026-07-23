package com.jacob0225.conduit;

import com.jacob0225.conduit.client.ConduitClient;
import com.jacob0225.conduit.network.PortDiscovery;
import com.jacob0225.conduit.server.ConduitServerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Conduit.MOD_ID)
public class Conduit {
    public static final String MOD_ID = "conduit";
    public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");
    public static final int DEFAULT_HTTP_PORT = 25566;
    private final ConduitServerRuntime serverRuntime = new ConduitServerRuntime();

    public Conduit(IEventBus modBus) {
        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::registerConfigurationTask);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        if (FMLEnvironment.dist == Dist.CLIENT) ConduitClient.initialize();
        LOGGER.info("Conduit NeoForge 1.1.0 initialized");
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.configurationToClient(PortDiscovery.Advertisement.TYPE,
                PortDiscovery.Advertisement.CODEC,
                FMLEnvironment.dist == Dist.CLIENT ? ConduitClient::handleAdvertisement : null);
        registrar.configurationToServer(PortDiscovery.Response.TYPE, PortDiscovery.Response.CODEC,
                (response, context) -> {
                    if (response.status() == PortDiscovery.NEEDS_SETUP) {
                        context.disconnect(Component.literal(
                                "Conduit is preparing the required mods. Reconnect after setup finishes."));
                    } else {
                        if (response.status() == PortDiscovery.ENDPOINT_UNREACHABLE) {
                            LOGGER.warn("Client could not reach the optional Conduit endpoint; allowing login");
                        }
                        context.finishCurrentTask(PortDiscovery.TASK_TYPE);
                    }
                });
    }

    private void registerConfigurationTask(RegisterConfigurationTasksEvent event) {
        ICommonPacketListener listener = (ICommonPacketListener) event.getListener();
        if (!listener.hasChannel(PortDiscovery.Advertisement.TYPE)) return;
        int port = serverRuntime.advertisedHttpPort();
        if (port <= 0) return;
        PortDiscovery.Advertisement payload = new PortDiscovery.Advertisement(
                port, serverRuntime.advertisedPublicBaseUrl());
        event.register(new ICustomConfigurationTask() {
            @Override public void run(java.util.function.Consumer<CustomPacketPayload> sender) { sender.accept(payload); }
            @Override public Type type() { return PortDiscovery.TASK_TYPE; }
        });
    }

    private void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        serverRuntime.start(server.getServerDirectory(), server.getServerVersion());
    }
    private void onServerStopped(ServerStoppedEvent event) { serverRuntime.stop(); }
}
