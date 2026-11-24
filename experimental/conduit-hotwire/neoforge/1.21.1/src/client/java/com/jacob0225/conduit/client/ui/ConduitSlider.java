package com.jacob0225.conduit.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * A 0–100 slider row: drag or click the track, or use the arrow keys.
 * Ticks a pitched note every 5 steps so you can "hear" the value.
 */
public class ConduitSlider extends AbstractWidget {

    private final String label;
    private final IntConsumer onChange;
    private final Runnable onCommit;
    private int value;
    private float shown;
    private long lastFrame;
    private boolean dragging;
    private boolean wasHovered;

    public ConduitSlider(int x, int y, int width, int height, String label, int value,
                         IntConsumer onChange, Runnable onCommit) {
        super(x, y, width, height, Component.literal(label));
        this.label = label;
        this.value = value;
        this.shown = value;
        this.onChange = onChange;
        this.onCommit = onCommit;
    }

    private int trackLeft() { return getX() + 8; }
    private int trackRight() { return getX() + getWidth() - 44; }

    private void setFromMouse(double mouseX) {
        float k = (float) ((mouseX - trackLeft()) / Math.max(1, trackRight() - trackLeft()));
        set(Math.round(ConduitUi.clamp01(k) * 100.0F));
    }

    private void set(int next) {
        next = Math.max(0, Math.min(100, next));
        if (next == value) return;
        int before = value / 5;
        value = next;
        onChange.accept(value);
        if (value / 5 != before) ConduitSounds.slider(value);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        dragging = true;
        setFromMouse(mouseX);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dx, double dy) {
        setFromMouse(mouseX);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        if (dragging) {
            dragging = false;
            float knobX = trackLeft() + (trackRight() - trackLeft()) * value / 100.0F;
            ConduitParticles.burst(knobX, getY() + getHeight() / 2.0F + 4, ConduitUi.ACCENT, 6, 45.0F);
            onCommit.run();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || !visible) return false;
        int step = net.minecraft.client.gui.screens.Screen.hasShiftDown() ? 10 : 5;
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) { set(value - step); onCommit.run(); return true; }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) { set(value + step); onCommit.run(); return true; }
        return false;
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        // Sliders speak through their value ticks instead of a click.
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        long now = ConduitUi.now();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        shown += (value - shown) * Math.min(1.0F, dt * 18.0F);

        boolean hot = active && isHovered();
        if (hot && !wasHovered) ConduitSounds.hover();
        wasHovered = hot;

        int x = getX();
        int y = getY();
        int r = x + getWidth();
        int b = y + getHeight();
        ConduitUi.roundRect(g, x, y, r, b, isHoveredOrFocused() || dragging ? ConduitUi.ROW_HOVER : ConduitUi.ROW);
        if (isFocused()) ConduitUi.roundOutline(g, x, y, r, b, ConduitUi.withAlpha(ConduitUi.ACCENT, 0x99));

        boolean tall = getHeight() >= 26;
        int labelY = tall ? y + 5 : y + (getHeight() - 8) / 2;
        if (tall) ConduitUi.text(g, label, x + 8, labelY, ConduitUi.TEXT);
        String pct = Math.round(shown) + "%";
        ConduitUi.text(g, pct, r - 8 - ConduitUi.font().width(pct), tall ? y + (getHeight() - 8) / 2 : labelY,
                value == 0 ? ConduitUi.DIM : ConduitUi.ACCENT);

        int tl = trackLeft();
        int tr = trackRight();
        int ty = tall ? y + getHeight() - 9 : y + getHeight() / 2 - 1;
        if (!tall) {
            tl = x + 8 + ConduitUi.font().width(label) + 10;
            ConduitUi.text(g, label, x + 8, labelY, ConduitUi.TEXT);
        }
        ConduitUi.roundRect(g, tl, ty, tr, ty + 4, 0xFF081019);
        int fx = tl + Math.round((tr - tl) * shown / 100.0F);
        if (fx > tl) ConduitUi.horizontalGradient(g, tl, ty, fx, ty + 4, ConduitUi.ACCENT_DEEP, ConduitUi.ACCENT);
        for (int i = 1; i < 4; i++) {
            int mx = tl + (tr - tl) * i / 4;
            g.fill(mx, ty + 1, mx + 1, ty + 3, 0x40FFFFFF);
        }
        int kw = dragging ? 7 : 6;
        ConduitUi.roundRect(g, fx - kw / 2, ty - 3, fx - kw / 2 + kw, ty + 7, 0xFFEAF6FF);
        g.fill(fx - kw / 2 + 1, ty + 5, fx - kw / 2 + kw - 1, ty + 6, 0xFFB5C8D6);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(label + ": " + value + " percent"));
    }
}
