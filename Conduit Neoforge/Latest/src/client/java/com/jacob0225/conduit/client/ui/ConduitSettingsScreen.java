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
    private static final int COL_MUTED = 0xFFB7C1CC;
    private static final int COL_GREEN = 0xFF65D887;
    private static final int COL_RED = 0xFFFF6B6B;
    private static final int COL_BORDER = 0x55FFFFFF;

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
        ClientConfig.Theme theme = config.theme();
        int panelWidth = Math.min(width - 28, 360);
        int left = width / 2 - panelWidth / 2;
        int y = panelTop() + 44;
        int buttonWidth = panelWidth - 48;

        addRenderableWidget(Button.builder(
                Component.literal("Theme: " + theme.name().toLowerCase()),
                btn -> {
                    config.cycleTheme();
                    save("Theme saved.");
                    init();
                }
        ).bounds(left + 24, y, buttonWidth, 18).build());
        y += 21;

        addToggle(left + 24, y, buttonWidth, "Compact download UI", config.compactDownloadUi(),
                value -> config.setCompactDownloadUi(value));
        y += 21;
        addToggle(left + 24, y, buttonWidth, "Auto-start downloads", config.autoStartDownloads(),
                value -> config.setAutoStartDownloads(value));
        y += 21;
        addToggle(left + 24, y, buttonWidth, "Show download details", config.showDownloadDetails(),
                value -> config.setShowDownloadDetails(value));
        y += 21;
        addToggle(left + 24, y, buttonWidth, "Dim backgrounds", config.dimBackground(),
                value -> config.setDimBackground(value));
        y += 21;
        addToggle(left + 24, y, buttonWidth, "Show optional mods", config.showOptionalMods(),
                value -> config.setShowOptionalMods(value));

        int bottomY = panelBottom() - 26;
        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(left + 24, bottomY, buttonWidth, 18)
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
        ClientConfig.Theme theme = config.theme();
        g.fill(0, 0, width, height, config.dimBackground() ? 0xB0000000 : theme.background());

        int left = panelLeft();
        int top = panelTop();
        int right = panelRight();
        int bottom = panelBottom();
        drawPanel(g, left, top, right, bottom, theme);

        drawCentered(g, "CONDUIT", width / 2, top + 18, theme.accentSoft());
        drawCentered(g, "Settings", width / 2, top + 32, COL_WHITE);
        drawCentered(g, "config/conduit/client.json", width / 2, bottom - 46, COL_MUTED);
        if (!footer.isEmpty()) {
            drawCentered(g, footer, width / 2, bottom - 35, footerColor);
        }

        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
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

    private int panelWidth() {
        return Math.min(width - 28, 360);
    }

    private int panelHeight() {
        return Math.min(height - 28, 252);
    }

    private int panelLeft() {
        return width / 2 - panelWidth() / 2;
    }

    private int panelRight() {
        return panelLeft() + panelWidth();
    }

    private int panelTop() {
        return height / 2 - panelHeight() / 2;
    }

    private int panelBottom() {
        return panelTop() + panelHeight();
    }

    private void drawPanel(GuiGraphicsExtractor g, int left, int top, int right, int bottom,
                           ClientConfig.Theme theme) {
        g.fill(left + 4, top + 5, right + 4, bottom + 5, 0x66000000);
        g.fill(left, top, right, bottom, theme.panel());
        g.fill(left, top, right, top + 2, theme.accent());
        g.fill(left, bottom - 1, right, bottom, COL_BORDER);
        g.fill(left, top, left + 1, bottom, COL_BORDER);
        g.fill(right - 1, top, right, bottom, COL_BORDER);
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int cx, int y, int color) {
        g.text(font, text, cx - font.width(text) / 2, y, color, true);
    }

    private interface ToggleSetter {
        void set(boolean value);
    }
}
