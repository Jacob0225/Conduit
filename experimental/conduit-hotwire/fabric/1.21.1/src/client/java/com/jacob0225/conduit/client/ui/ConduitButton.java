package com.jacob0225.conduit.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Flat Conduit-styled button: tinted by {@link Style}, lifts on hover, sinks
 * when pressed, and plays layered feedback sounds.
 */
public class ConduitButton extends AbstractButton {

    public enum Style { PRIMARY, SECONDARY, WARNING, DANGER }

    private final Consumer<ConduitButton> action;
    private Style style;
    private boolean wasHovered;
    private long pressedAt;
    private float hoverAnim;
    private long lastFrame;

    public ConduitButton(int x, int y, int width, int height, Component message,
                         Style style, Consumer<ConduitButton> action) {
        super(x, y, width, height, message);
        this.style = style;
        this.action = action;
    }

    public ConduitButton style(Style style) {
        this.style = style;
        return this;
    }

    public ConduitButton tooltip(String text) {
        setTooltip(text == null ? null : Tooltip.create(Component.literal(text)));
        return this;
    }

    public void place(int x, int y, int width) {
        setX(x);
        setY(y);
        setWidth(width);
    }

    @Override
    public void onPress() {
        pressedAt = ConduitUi.now();
        int color = switch (style) {
            case PRIMARY -> ConduitUi.ACCENT;
            case WARNING -> ConduitUi.YELLOW;
            case DANGER -> ConduitUi.RED;
            default -> ConduitUi.MUTED;
        };
        ConduitParticles.burst(getX() + getWidth() / 2.0F, getY() + getHeight() / 2.0F, color,
                style == Style.PRIMARY ? 14 : 8, style == Style.PRIMARY ? 90.0F : 60.0F);
        action.accept(this);
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        ConduitSounds.press(switch (style) {
            case PRIMARY -> ConduitSounds.Press.PRIMARY;
            case WARNING, DANGER -> ConduitSounds.Press.WARNING;
            case SECONDARY -> getMessage().getString().startsWith("Back")
                    || getMessage().getString().startsWith("Cancel")
                    ? ConduitSounds.Press.BACK : ConduitSounds.Press.NORMAL;
        });
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        long now = ConduitUi.now();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;

        boolean hot = active && isHovered();
        if (hot && !wasHovered) ConduitSounds.hover();
        wasHovered = hot;
        float target = active && isHoveredOrFocused() ? 1.0F : 0.0F;
        hoverAnim += (target - hoverAnim) * Math.min(1.0F, dt * 14.0F);

        boolean pressed = now - pressedAt < 110L;
        int lift = !active ? 0 : pressed ? 1 : (hoverAnim > 0.5F ? -1 : 0);

        int x = getX();
        int y = getY() + lift;
        int r = x + getWidth();
        int b = y + getHeight();

        int base;
        int edge;
        int label;
        switch (style) {
            case PRIMARY -> { base = ConduitUi.ACCENT_DEEP; edge = ConduitUi.ACCENT; label = 0xFFFFFFFF; }
            case WARNING -> { base = 0xFF3A3016; edge = ConduitUi.YELLOW; label = ConduitUi.YELLOW; }
            case DANGER -> { base = 0xFF3A1A1E; edge = ConduitUi.RED; label = 0xFFFFC9C9; }
            default -> { base = 0xFF172A3A; edge = 0xFF2E5470; label = ConduitUi.TEXT; }
        }
        if (!active) {
            base = 0xFF111C27;
            edge = 0xFF1E3142;
            label = ConduitUi.DIM;
        }
        int fill = ConduitUi.lerpColor(base, ConduitUi.lerpColor(base, 0xFFFFFFFF, 0.18F), hoverAnim);
        int rim = ConduitUi.lerpColor(edge, 0xFFFFFFFF, hoverAnim * 0.35F);

        // Shadow stays put so the face visibly lifts and sinks.
        ConduitUi.roundRect(g, x, getY() + 2, r, getY() + getHeight() + 2, 0x80000000);
        ConduitUi.roundRect(g, x, y, r, b, rim);
        ConduitUi.roundRect(g, x + 1, y + 1, r - 1, b - 1, fill);
        if (active) {
            g.fill(x + 2, y + 1, r - 2, y + 2, ConduitUi.withAlpha(0xFFFFFF, (int) (0x22 + hoverAnim * 0x30)));
            if (style == Style.PRIMARY) {
                ConduitUi.horizontalGradient(g, x + 1, b - 2, r - 1, b - 1,
                        ConduitUi.withAlpha(ConduitUi.ACCENT_2, 0x90), ConduitUi.withAlpha(ConduitUi.ACCENT, 0x90));
            }
        }

        String text = ConduitUi.trim(getMessage().getString(), getWidth() - 8);
        int tx = x + (getWidth() - ConduitUi.font().width(text)) / 2;
        int ty = y + (getHeight() - 8) / 2;
        g.drawString(ConduitUi.font(), text, tx, ty, label, active);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
