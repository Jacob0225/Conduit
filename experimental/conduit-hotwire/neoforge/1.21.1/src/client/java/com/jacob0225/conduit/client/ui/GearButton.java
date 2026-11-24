package com.jacob0225.conduit.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * Blue gear on the title screen. It spins up while hovered and, when
 * clicked, grows into the middle of the screen while the backdrop fades in,
 * then hands over to {@link ConduitSettingsScreen}, which continues the
 * zoom so the whole thing reads as one smooth motion.
 */
public class GearButton extends AbstractButton {

    /** How long the title-screen half of the zoom lasts. */
    static final long TRANSITION_MS = 320L;
    /** Gear size at the hand-over point; the settings screen starts from here. */
    static final float HANDOFF_SIZE = 96.0F;

    private final Screen screen;
    private float angle;
    private float speed;
    private float hoverAnim;
    private long lastFrame;
    private boolean wasHovered;
    private long transitionAt = -1L;
    private boolean opened;
    private float sparkleClock;

    public GearButton(Screen screen, int x, int y) {
        super(x, y, 20, 20, Component.literal("Conduit settings"));
        this.screen = screen;
        setTooltip(Tooltip.create(Component.literal("Conduit settings")));
    }

    @Override
    public void onPress() {
        if (transitionAt >= 0) return;
        transitionAt = ConduitUi.now();
        setTooltip(null); // don't leave the tooltip floating over the zoom
        ConduitParticles.burst(centerX(), centerY(), ConduitUi.ACCENT, 18, 110.0F);
        if (!ConduitUi.animations()) open();
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        ConduitSounds.gearOpen();
    }

    private float centerX() { return getX() + getWidth() / 2.0F; }
    private float centerY() { return getY() + getHeight() / 2.0F; }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        long now = ConduitUi.now();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;

        boolean hot = active && isHovered();
        if (hot && !wasHovered) ConduitSounds.gearHover();
        wasHovered = hot;
        hoverAnim += ((isHoveredOrFocused() ? 1.0F : 0.0F) - hoverAnim) * Math.min(1.0F, dt * 12.0F);

        // Spin: ease up to speed while hovered, coast to a stop otherwise.
        float target = transitionAt >= 0 ? 14.0F : hot ? 4.5F : 0.0F;
        if (!ConduitUi.animations()) target = 0.0F;
        speed += (target - speed) * Math.min(1.0F, dt * (target > speed ? 6.0F : 2.5F));
        angle += speed * dt;

        if (hot && ConduitUi.animations()) {
            sparkleClock += dt;
            while (sparkleClock > 0.12F) {
                sparkleClock -= 0.12F;
                ConduitParticles.sparkle(centerX(), centerY() - 4, ConduitUi.ACCENT_2);
            }
        }

        if (transitionAt >= 0) return; // the zoom overlay draws the gear from here on

        int x = getX();
        int y = getY();
        int r = x + getWidth();
        int b = y + getHeight();
        int tile = ConduitUi.lerpColor(0xB0101C28, 0xE0182C3C, hoverAnim);
        ConduitUi.roundRect(g, x, y, r, b, tile);
        ConduitUi.roundOutline(g, x, y, r, b, ConduitUi.lerpColor(0xFF24425A, ConduitUi.ACCENT, hoverAnim));
        float size = 16.0F + 2.0F * hoverAnim;
        ConduitUi.gear(g, centerX(), centerY(), size, angle);
    }

    /**
     * Drawn after the whole title screen (Fabric's after-render event), so the
     * fade covers the logo, splash text and other buttons too.
     */
    public void renderOverlay(GuiGraphics g, int width, int height) {
        if (transitionAt < 0) {
            ConduitParticles.render(g); // hover sparkles
            return;
        }
        float t = ConduitUi.clamp01((ConduitUi.now() - transitionAt) / (float) TRANSITION_MS);
        float e = ConduitUi.easeInOutCubic(t);
        ConduitUi.backdrop(g, width, height, e);
        float cx = centerX() + (width / 2.0F - centerX()) * e;
        float cy = centerY() + (height / 2.0F - centerY()) * e;
        float size = 18.0F + (HANDOFF_SIZE - 18.0F) * e;
        // Soft glow ring that grows with the gear.
        int glow = ConduitUi.withAlpha(ConduitUi.ACCENT, (int) (0x40 * e));
        int gr = Math.round(size * 0.65F);
        ConduitUi.roundRect(g, Math.round(cx) - gr, Math.round(cy) - gr, Math.round(cx) + gr, Math.round(cy) + gr, glow);
        ConduitUi.gear(g, cx, cy, size, angle);
        ConduitParticles.render(g);
        if (t >= 1.0F) open();
    }

    private void open() {
        if (opened) return;
        opened = true;
        float handoffAngle = angle;
        Minecraft mc = Minecraft.getInstance();
        // Switch screens outside of rendering.
        mc.execute(() -> mc.setScreen(new ConduitSettingsScreen(screen, true, handoffAngle)));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
