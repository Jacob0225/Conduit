package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.client.util.InstanceRestarter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation screen shown when the player is switching to a server whose
 * previously installed mod set has been restored from the Conduit stash.
 */
public class ModSwitchScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Switch");

    private static final int COL_WHITE = 0xFFFFFFFF;
    private static final int COL_MUTED = 0xFFB7C1CC;
    private static final int COL_GREEN = 0xFF65D887;
    private static final int COL_YELLOW = 0xFFFFD166;
    private static final int COL_BORDER = 0x55FFFFFF;

    private final ClientConfig config = ClientConfig.get();
    private final ClientConfig.Theme theme = config.theme();
    private final String serverName;
    private final Screen parent;

    public ModSwitchScreen(String serverName, Screen parent) {
        super(Component.literal("Conduit - Server Switch"));
        this.serverName = serverName != null ? serverName : "this server";
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(this.width - 28, 500);
        int left = this.width / 2 - panelWidth / 2;
        int by = this.height / 2 + 76;
        int buttonWidth = Math.min(150, (panelWidth - 58) / 2);

        addRenderableWidget(Button.builder(
                Component.literal("Restart & Join"),
                btn -> {
                    LOGGER.info("Player confirmed restart for server switch to '{}'", serverName);
                    InstanceRestarter.restart();
                }
        ).bounds(left + 24, by, buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Join Anyway"),
                btn -> {
                    LOGGER.info("Player chose Join Anyway for '{}' - skipping swap restart", serverName);
                    ConduitJoinInterceptor.bypassNextCheck();
                    this.onClose();
                }
        ).bounds(left + panelWidth - 24 - buttonWidth, by, buttonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        if (config.dimBackground()) {
            g.fill(0, 0, this.width, this.height, 0xB0000000);
        } else {
            g.fill(0, 0, this.width, this.height, theme.background());
        }

        int width = Math.min(this.width - 28, 500);
        int height = 220;
        int left = this.width / 2 - width / 2;
        int top = this.height / 2 - height / 2;
        int right = left + width;
        int bottom = top + height;

        drawPanel(g, left, top, right, bottom);

        int cx = this.width / 2;
        int y = top + 22;
        drawCentered(g, "CONDUIT MOD SYNC", cx, y, theme.accentSoft());
        y += 38;
        drawCentered(g, "Switching to " + serverName, cx, y, COL_WHITE);
        y += 18;
        drawCentered(g, "Conduit restored this server's saved mod set.", cx, y, COL_MUTED);
        y += 14;
        drawCentered(g, "Restart Minecraft so Fabric can load the new jars.", cx, y, COL_MUTED);
        y += 22;
        drawCentered(g, "After restart, reconnect to " + serverName + ".", cx, y, COL_GREEN);
        y += 16;
        drawCentered(g, "Join Anyway may disconnect if the current loaded mods differ.", cx, y, COL_YELLOW);

        super.render(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void drawPanel(GuiGraphics g, int left, int top, int right, int bottom) {
        g.fill(left + 4, top + 5, right + 4, bottom + 5, 0x66000000);
        g.fill(left, top, right, bottom, theme.panel());
        g.fill(left, top, right, top + 2, theme.accent());
        g.fill(left, bottom - 1, right, bottom, COL_BORDER);
        g.fill(left, top, left + 1, bottom, COL_BORDER);
        g.fill(right - 1, top, right, bottom, COL_BORDER);
    }

    private void drawCentered(GuiGraphics g, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, true);
    }
}
