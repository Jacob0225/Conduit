package com.jacob0225.conduit.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/** A settings row: label + description on the left, a sliding switch on the right. */
public class ConduitToggle extends AbstractButton {

    public interface Listener {
        void changed(ConduitToggle toggle, boolean value);
    }

    private final String label;
    private final String description;
    private final Listener listener;
    private boolean value;
    private float knob;
    private long lastFrame;
    private boolean wasHovered;

    public ConduitToggle(int x, int y, int width, int height, String label, String description,
                         boolean value, Listener listener) {
        super(x, y, width, height, Component.literal(label));
        this.label = label;
        this.description = description;
        this.value = value;
        this.knob = value ? 1.0F : 0.0F;
        this.listener = listener;
    }

    public boolean value() {
        return value;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        value = !value;
        int tx = getX() + getWidth() - 8 - 26;
        ConduitParticles.burst(value ? tx + 20 : tx + 6, getY() + getHeight() / 2.0F,
                value ? ConduitUi.ACCENT : ConduitUi.DIM, value ? 10 : 5, value ? 70.0F : 40.0F);
        listener.changed(this, value);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        // The toggle sound depends on the new state, so it is played after the flip.
        ConduitSounds.toggle(!value);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        long now = ConduitUi.now();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        knob += ((value ? 1.0F : 0.0F) - knob) * Math.min(1.0F, dt * 16.0F);

        boolean hot = active && isHovered();
        if (hot && !wasHovered) ConduitSounds.hover();
        wasHovered = hot;

        int x = getX();
        int y = getY();
        int r = x + getWidth();
        int b = y + getHeight();
        ConduitUi.roundRect(g, x, y, r, b, isHoveredOrFocused() ? ConduitUi.ROW_HOVER : ConduitUi.ROW);
        if (isFocused()) ConduitUi.roundOutline(g, x, y, r, b, ConduitUi.withAlpha(ConduitUi.ACCENT, 0x99));

        ConduitUi.text(g, label, x + 8, y + 5, ConduitUi.TEXT);
        if (description != null && !description.isEmpty() && getHeight() >= 26) {
            g.text(ConduitUi.font(), ConduitUi.trim(description, getWidth() - 58), x + 8, y + 16, ConduitUi.MUTED, false);
        }

        // Switch track + knob.
        int tw = 26;
        int th = 12;
        int tx = r - tw - 8;
        int ty = y + (getHeight() - th) / 2;
        int track = ConduitUi.lerpColor(0xFF2A3B4B, ConduitUi.ACCENT_DEEP, knob);
        ConduitUi.roundRect(g, tx, ty, tx + tw, ty + th, track);
        ConduitUi.roundOutline(g, tx, ty, tx + tw, ty + th, ConduitUi.lerpColor(0xFF3A5064, ConduitUi.ACCENT, knob));
        int kx = tx + 1 + Math.round((tw - th) * knob);
        ConduitUi.roundRect(g, kx, ty + 1, kx + th - 2, ty + th - 1, 0xFFEAF6FF);
        g.fill(kx + 1, ty + th - 2, kx + th - 3, ty + th - 1, 0xFFB5C8D6);
        String state = value ? "ON" : "OFF";
        int sw = ConduitUi.font().width(state);
        g.text(ConduitUi.font(), state, tx - sw - 6, ty + 2, value ? ConduitUi.ACCENT : ConduitUi.DIM, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(label + ": " + (value ? "on" : "off")));
    }
}
