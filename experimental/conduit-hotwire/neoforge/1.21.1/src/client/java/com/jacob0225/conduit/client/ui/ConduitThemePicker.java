package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/** Accent theme row: click to cycle (Shift-click backwards); swatches show every choice. */
public class ConduitThemePicker extends AbstractButton {

    private final Consumer<String> onChange;
    private int index;
    private long changedAt = -10_000L;
    private boolean wasHovered;

    public ConduitThemePicker(int x, int y, int width, int height, String current, Consumer<String> onChange) {
        super(x, y, width, height, Component.literal("Accent theme"));
        this.index = Math.max(0, ClientConfig.THEMES.indexOf(current));
        this.onChange = onChange;
    }

    @Override
    public void onPress() {
        List<String> themes = ClientConfig.THEMES;
        index = Math.floorMod(index + (net.minecraft.client.gui.screens.Screen.hasShiftDown() ? -1 : 1), themes.size());
        changedAt = ConduitUi.now();
        onChange.accept(themes.get(index));
        ConduitSounds.cycle(index);
        int sx = swatchX(index);
        ConduitParticles.burst(sx + 4, getY() + getHeight() / 2.0F, ConduitUi.ACCENT, 16, 85.0F);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        // The theme chord plays from onPress, once the new theme is known.
    }

    private int swatchX(int i) {
        int count = ClientConfig.THEMES.size();
        return getX() + getWidth() - 8 - (count - i) * 13 + 3;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        boolean hot = active && isHovered();
        if (hot && !wasHovered) ConduitSounds.hover();
        wasHovered = hot;

        int x = getX();
        int y = getY();
        int r = x + getWidth();
        int b = y + getHeight();
        ConduitUi.roundRect(g, x, y, r, b, isHoveredOrFocused() ? ConduitUi.ROW_HOVER : ConduitUi.ROW);
        if (isFocused()) ConduitUi.roundOutline(g, x, y, r, b, ConduitUi.withAlpha(ConduitUi.ACCENT, 0x99));

        String theme = ClientConfig.THEMES.get(index);
        boolean tall = getHeight() >= 26;
        int ly = tall ? y + 5 : y + (getHeight() - 8) / 2;
        ConduitUi.text(g, "Accent theme", x + 8, ly, ConduitUi.TEXT);
        String name = ConduitUi.themeLabel(theme);
        float pop = ConduitUi.clamp01((ConduitUi.now() - changedAt) / 250.0F);
        int nameX = x + 8 + (tall ? 0 : ConduitUi.font().width("Accent theme") + 8);
        int nameY = tall ? y + 16 : ly;
        g.drawString(ConduitUi.font(), name, nameX, nameY + Math.round((1.0F - ConduitUi.easeOutBack(pop)) * 4.0F),
                ConduitUi.ACCENT, false);

        for (int i = 0; i < ClientConfig.THEMES.size(); i++) {
            int[] c = ConduitUi.themeColors(ClientConfig.THEMES.get(i));
            int sx = swatchX(i);
            int sy = y + (getHeight() - 9) / 2;
            boolean sel = i == index;
            if (sel) ConduitUi.roundOutline(g, sx - 2, sy - 2, sx + 11, sy + 11, 0xFFEAF6FF);
            ConduitUi.roundRect(g, sx, sy, sx + 9, sy + 9, c[2]);
            g.fill(sx + 1, sy + 1, sx + 8, sy + 5, c[0]);
            g.fill(sx + 1, sy + 5, sx + 8, sy + 8, c[1]);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal("Accent theme: "
                + ConduitUi.themeLabel(ClientConfig.THEMES.get(index))));
    }
}
