package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.client.util.InstanceRestarter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation screen shown when the player is joining a server whose mods are
 * all installed but not currently active — i.e. switching from another server's
 * mod set. Fabric loads every jar in {@code mods/} at startup, so the target
 * server's jars must be swapped in (from the stash) and the instance restarted
 * before the join will work.
 *
 * <p>The stash reconcile has already happened by the time this screen shows; the
 * player just confirms the restart. "Join Anyway" skips the restart and connects
 * with the current (wrong) mod set — they'll likely get disconnected, but it's
 * their choice.
 */
public class ModSwitchScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Switch");

    private static final int COL_WHITE  = 0xFFFFFFFF;
    private static final int COL_GREY   = 0xFFAAAAAA;
    private static final int COL_YELLOW = 0xFFFFCC00;
    private static final int COL_GREEN  = 0xFF55FF55;
    private static final int PANEL_COLOR = 0xCC000000;

    private final String serverName;
    private final Screen parent;

    public ModSwitchScreen(String serverName, Screen parent) {
        super(Component.literal("Conduit — Server Switch"));
        this.serverName = serverName != null ? serverName : "this server";
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height - 36;

        addRenderableWidget(Button.builder(
                Component.literal("Restart & Join"),
                btn -> {
                    LOGGER.info("Player confirmed restart for server switch to '{}'", serverName);
                    InstanceRestarter.restart();
                }
        ).bounds(cx - 154, by, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Join Anyway"),
                btn -> {
                    // Bypass and let the current (possibly wrong) mod set stand.
                    LOGGER.info("Player chose Join Anyway for '{}' — skipping swap restart", serverName);
                    ConduitJoinInterceptor.bypassNextCheck();
                    this.onClose();
                }
        ).bounds(cx + 4, by, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, this.width, this.height, PANEL_COLOR);

        int cx = this.width / 2;
        int y  = this.height / 2 - 50;

        drawCentered(g, "§lSwitching to " + serverName, cx, y, COL_WHITE);
        y += 18;
        drawCentered(g, "This server needs a different mod set than the one", cx, y, COL_GREY);
        y += 12;
        drawCentered(g, "currently loaded. Conduit has swapped the mods for you.", cx, y, COL_GREY);
        y += 16;
        drawCentered(g, "Click Restart & Join to reload the game with the new mods.", cx, y, COL_YELLOW);
        y += 12;
        drawCentered(g, "After restart, reconnect to " + serverName + ".", cx, y, COL_GREEN);

        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int cx, int y, int color) {
        g.text(font, text, cx - font.width(text) / 2, y, color, true);
    }
}
