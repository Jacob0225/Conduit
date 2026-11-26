package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.ui.ConduitSettingsScreen;
import com.jacob0225.conduit.client.ui.ConduitUi;
import com.jacob0225.conduit.client.ui.GearButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import com.jacob0225.conduit.network.PortDiscovery;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConduitClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");
    private ConduitClient() {}
    public static void initialize(ModContainer modContainer) {
        ConduitUi.applyTheme(ClientConfig.load().theme());
        dev.conduit.hotload.LiveLoad.prepare(); // find a Java that can attach, before it's needed
        com.jacob0225.conduit.client.devtest.AutoJoinTest.installIfRequested(); // dev-only, needs -Dconduit.autotest
        verifyInstallRecords();
        IConfigScreenFactory configScreenFactory =
                (container, parent) -> new ConduitSettingsScreen(parent);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, configScreenFactory);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ConduitJoinInterceptor.onClientTick();
            com.jacob0225.conduit.client.network.ConduitPreflight.onClientTick();
        });
        NeoForge.EVENT_BUS.addListener(ConduitClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ConduitClient::onScreenRender);
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
