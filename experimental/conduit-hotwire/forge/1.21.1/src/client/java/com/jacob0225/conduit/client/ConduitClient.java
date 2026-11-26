package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.client.ui.ConduitSettingsScreen;
import com.jacob0225.conduit.client.ui.ConduitUi;
import com.jacob0225.conduit.client.ui.GearButton;
import com.jacob0225.conduit.network.PortDiscovery;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class ConduitClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");
    private static SimpleChannel channel;
    private ConduitClient() {}

    public static void bindChannel(SimpleChannel value) { channel = value; }

    public static void initialize(FMLJavaModLoadingContext context) {
        ConduitUi.applyTheme(ClientConfig.load().theme());
        dev.conduit.hotload.LiveLoad.prepare(); // find a Java that can attach, before it's needed
        com.jacob0225.conduit.client.devtest.AutoJoinTest.installIfRequested(); // dev-only, needs -Dconduit.autotest
        verifyInstallRecords();
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ConduitSettingsScreen(parent)));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                ConduitJoinInterceptor.onClientTick();
                com.jacob0225.conduit.client.network.ConduitPreflight.onClientTick();
            }
        });
        MinecraftForge.EVENT_BUS.addListener(ConduitClient::onScreenInit);
        MinecraftForge.EVENT_BUS.addListener(ConduitClient::onScreenRender);
        LOGGER.info("Conduit Forge client ready");
    }

    /** Runs on the network thread; the reply goes out once the manifest check finishes. */
    public static void handleAdvertisement(PortDiscovery.Advertisement advertisement, CustomPayloadEvent.Context context) {
        var connection = context.getConnection();
        ConduitJoinInterceptor.validateAdvertisement(advertisement.endpoint()).whenComplete((status, error) -> {
            byte response = error == null ? status : PortDiscovery.ENDPOINT_UNREACHABLE;
            channel.send(new PortDiscovery.Response(response), connection);
            if (response == PortDiscovery.NEEDS_SETUP) {
                connection.channel().closeFuture().addListener(future ->
                        ConduitJoinInterceptor.onLoginDisconnect());
            }
        });
    }

    // Blue Conduit gear in the top-right corner of the main menu.
    private static Screen gearScreen;
    private static GearButton gear;

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen) || !ClientConfig.get().titleScreenButton()) return;
        gear = new GearButton(screen, screen.width - 26, 6);
        gearScreen = screen;
        event.addListener(gear);
    }

    private static void onScreenRender(ScreenEvent.Render.Post event) {
        if (gear != null && event.getScreen() == gearScreen) {
            gear.renderOverlay(event.getGuiGraphics(), gearScreen.width, gearScreen.height);
        }
    }

    private static void verifyInstallRecords() {
        Path gameDir = PlatformPaths.gameDir();
        for (ServerInstallRecord rec : ServerInstallRecord.allRecords()) {
            // A jar shared with another server may sit in that server's stash.
            boolean stale = rec.ownedJarFilenames().stream()
                    .anyMatch(fn -> !ModStashManager.isAvailable(gameDir, fn));
            if (stale) rec.delete();
        }
    }
}
