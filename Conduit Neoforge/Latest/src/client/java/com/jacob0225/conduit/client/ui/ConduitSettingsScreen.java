package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ConduitSettingsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Settings");

    private static final int COL_WHITE = 0xFFFFFFFF;
    private static final int COL_MUTED = 0xFFA0A0A0;
    private static final int COL_GREEN = 0xFF55FF55;
    private static final int COL_RED = 0xFFFF5555;
    private static final int COL_OCEAN = 0xFF35B9E8;

    private final Screen parent;
    private ClientConfig config;
    private String footer = "";
    private int footerColor = COL_MUTED;

    public ConduitSettingsScreen(Screen parent) {
        super(Component.literal("Conduit Settings"));
        this.parent = parent;
        this.config = ClientConfig.get();
    }

    @Override
    protected void init() {
        clearWidgets();
        int panelWidth = Math.min(width - 28, 360);
        int left = width / 2 - panelWidth / 2;
        int y = height / 2 - 22;
        int buttonWidth = panelWidth - 48;

        addToggle(left + 24, y, buttonWidth, "Compact download UI", config.compactDownloadUi(),
                value -> config.setCompactDownloadUi(value));
        y += 21;
        addToggle(left + 24, y, buttonWidth, "Auto-start downloads", config.autoStartDownloads(),
                value -> config.setAutoStartDownloads(value));
        y += 21;
        addToggle(left + 24, y, buttonWidth, "Show optional mods", config.showOptionalMods(),
                value -> config.setShowOptionalMods(value));
        y += 29;

        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(left + 24, y, buttonWidth, 18)
                .build());
    }

    private void addToggle(int x, int y, int width, String label, boolean value, ToggleSetter setter) {
        addRenderableWidget(Button.builder(Component.literal(label + ": " + onOff(value)), btn -> {
            boolean next = !value;
            setter.set(next);
            save(label + " " + onOff(next).toLowerCase() + ".");
            init();
        }).bounds(x, y, width, 18).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (parent != null) parent.extractRenderState(g, mx, my, delta);
        drawCentered(g, "Conduit Settings", width / 2, height / 2 - 70, COL_OCEAN);
        drawCentered(g, "config/conduit/client.json", width / 2, height / 2 - 56, COL_MUTED);
        if (!footer.isEmpty()) {
            drawCentered(g, footer, width / 2, height / 2 + 91, footerColor);
        }

        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private void save(String message) {
        try {
            config.save();
            footer = message;
            footerColor = COL_GREEN;
        } catch (IOException e) {
            LOGGER.warn("Failed to save Conduit client config: {}", e.getMessage());
            footer = "Could not save config.";
            footerColor = COL_RED;
        }
    }

    private static String onOff(boolean value) {
        return value ? "On" : "Off";
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int cx, int y, int color) {
        g.text(font, text, cx - font.width(text) / 2, y, color, true);
    }

    private interface ToggleSetter {
        void set(boolean value);
    }
}
