package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.network.PortDiscovery;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import com.jacob0225.conduit.client.ui.GearButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ClientModInitializer;

import java.nio.file.Path;

/**
 * Client-side entrypoint for Conduit.
 *
 * <p>The login-query receiver discovers the server's HTTP port over the normal
 * Minecraft connection. The manifest itself remains out-of-band HTTP data.
 *
 * <p>The one piece of init-time work is a <b>sanity check</b> on the per-server
 * install records: if a jar a record claims is installed has vanished from both
 * {@code mods/} and the stash (the player deleted it, an external tool cleaned
 * up, etc.), that record can't be trusted to satisfy a join — invalidate it so
 * the next join to that server re-prompts and re-downloads instead of falsely
 * passing the player through to a registry mismatch.
 */
public class ConduitClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    @Override
    public void onInitializeClient() {
        ClientConfig config = ClientConfig.load();
        com.jacob0225.conduit.client.ui.ConduitUi.applyTheme(config.theme());
        verifyInstallRecords();
        registerTitleScreenGear();
        ClientLoginNetworking.registerGlobalReceiver(PortDiscovery.CHANNEL,
                (client, handler, buf, callbacks) -> {
                    final PortDiscovery.Endpoint endpoint;
                    try {
                        endpoint = PortDiscovery.readEndpoint(buf);
                    } catch (RuntimeException e) {
                        LOGGER.warn("Server sent an invalid Conduit port advertisement: {}", e.getMessage());
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                PortDiscovery.response(PortDiscovery.ENDPOINT_UNREACHABLE));
                    }
                    return ConduitJoinInterceptor.validateAdvertisement(endpoint)
                            .thenApply(PortDiscovery::response);
                });
        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) ->
                ConduitJoinInterceptor.onLoginDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(client -> ConduitJoinInterceptor.onClientTick());
        LOGGER.info("Conduit client ready — login port discovery active");
    }

    /** Adds the blue Conduit gear to the top-right corner of the main menu. */
    private void registerTitleScreenGear() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen) || !ClientConfig.get().titleScreenButton()) return;
            GearButton gear = new GearButton(screen, width - 26, 6);
            Screens.getWidgets(screen).add(gear);
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) ->
                    gear.renderOverlay(graphics, s.width, s.height));
        });
    }

    /**
     * For each per-server install record, confirm every jar it claims to own is
     * still findable — either in {@code mods/} (active) or in the stash (swapped
     * out for another server). Any record missing jars is deleted so the next
     * join to that server re-downloads instead of passing through unsatisfied.
     */
    private void verifyInstallRecords() {
        Path gameDir = FabricLoader.getInstance().getGameDir();

        int checked = 0;
        int invalidated = 0;
        for (ServerInstallRecord rec : ServerInstallRecord.allRecords()) {
            checked++;
            boolean stale = false;
            for (String fn : rec.ownedJarFilenames()) {
                // A jar shared with another server may be stashed under that
                // server's folder, so look in every stash before giving up.
                if (!ModStashManager.isAvailable(gameDir, fn)) {
                    LOGGER.warn("Record for '{}' references jar '{}' that is missing "
                            + "from both mods/ and the stash — invalidating record.",
                            rec.serverName(), fn);
                    stale = true;
                    break;
                }
            }
            if (stale) {
                rec.delete();
                invalidated++;
            }
        }
        if (checked > 0) {
            LOGGER.info("Install-record sanity check: {} record(s) checked, {} invalidated.",
                    checked, invalidated);
        }
    }
}
