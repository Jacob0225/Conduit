package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tiny 2D particle system for Conduit's screens: bursts on clicks and
 * successes, drifting sparkles on hover. Everything is GUI-space, time-based
 * (so frame rate doesn't change the motion) and capped so it can't pile up.
 * Called on the render thread only.
 */
public final class ConduitParticles {

    private static final int MAX = 600;
    private static final List<P> PARTICLES = new ArrayList<>();
    private static long lastFrame;

    private static final class P {
        float x, y, vx, vy, life, age, size, gravity, spin;
        int color;
        boolean square;
    }

    private ConduitParticles() {}

    private static boolean enabled() {
        return ClientConfig.get().particles();
    }

    private static float rnd(float min, float max) {
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }

    private static void add(float x, float y, float vx, float vy, float life, float size, float gravity, int color, boolean square) {
        if (PARTICLES.size() >= MAX) PARTICLES.remove(0);
        P p = new P();
        p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.life = life; p.size = size; p.gravity = gravity;
        p.color = color; p.square = square; p.spin = rnd(0.0F, 6.28F);
        PARTICLES.add(p);
    }

    /** Radial burst, e.g. when a button is pressed or a mod finishes. */
    public static void burst(float x, float y, int color, int count, float speed) {
        if (!enabled()) return;
        for (int i = 0; i < count; i++) {
            double a = rnd(0.0F, (float) (Math.PI * 2));
            float v = rnd(speed * 0.35F, speed);
            int c = ThreadLocalRandom.current().nextInt(4) == 0 ? 0xFFFFFFFF : color;
            add(x, y, (float) Math.cos(a) * v, (float) Math.sin(a) * v - speed * 0.25F,
                    rnd(0.45F, 0.9F), rnd(1.0F, 2.5F), 160.0F, c, true);
        }
    }

    /** A wide shower along a line, used for big successes (the progress bar). */
    public static void shower(float left, float right, float y, int color, int count) {
        if (!enabled()) return;
        for (int i = 0; i < count; i++) {
            add(rnd(left, right), y, rnd(-30.0F, 30.0F), rnd(-140.0F, -50.0F),
                    rnd(0.6F, 1.2F), rnd(1.0F, 2.5F), 190.0F, i % 5 == 0 ? 0xFFFFFFFF : color, true);
        }
    }

    /** A single slow, floating sparkle (hover effects). */
    public static void sparkle(float x, float y, int color) {
        if (!enabled()) return;
        add(x + rnd(-3.0F, 3.0F), y + rnd(-3.0F, 3.0F), rnd(-8.0F, 8.0F), rnd(-22.0F, -8.0F),
                rnd(0.5F, 0.9F), rnd(1.0F, 1.8F), -4.0F, color, false);
    }

    /** Rising bubbles from a point, a gentle "conduit" effect. */
    public static void bubbles(float x, float y, int count) {
        if (!enabled()) return;
        for (int i = 0; i < count; i++) {
            add(x + rnd(-10.0F, 10.0F), y + rnd(-4.0F, 4.0F), rnd(-6.0F, 6.0F), rnd(-60.0F, -25.0F),
                    rnd(0.8F, 1.5F), rnd(1.5F, 3.0F), -10.0F, 0xFF9FE6FF, false);
        }
    }

    public static void clear() {
        PARTICLES.clear();
    }

    /** Advance and draw every live particle. Call once per frame, after the screen's widgets. */
    public static void render(GuiGraphics g) {
        long now = ConduitUi.now();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.05F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        if (PARTICLES.isEmpty()) return;
        Iterator<P> it = PARTICLES.iterator();
        while (it.hasNext()) {
            P p = it.next();
            p.age += dt;
            if (p.age >= p.life) {
                it.remove();
                continue;
            }
            p.vy += p.gravity * dt;
            p.vx *= (float) Math.pow(0.35, dt);
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            float k = 1.0F - p.age / p.life;
            int alpha = (int) (0xFF * Math.min(1.0F, k * 1.6F));
            float size = p.size * (0.5F + 0.5F * k);
            int s = Math.max(1, Math.round(size));
            int x = Math.round(p.x);
            int y = Math.round(p.y);
            if (p.square) {
                g.fill(x - s - 1, y - s - 1, x + s + 1, y + s + 1, ConduitUi.withAlpha(p.color, alpha / 5));
                g.fill(x - s / 2, y - s / 2, x - s / 2 + s, y - s / 2 + s, ConduitUi.withAlpha(p.color, alpha));
            } else {
                // Hollow bubble / twinkle.
                g.fill(x - s, y, x + s + 1, y + 1, ConduitUi.withAlpha(p.color, alpha * 2 / 3));
                g.fill(x, y - s, x + 1, y + s + 1, ConduitUi.withAlpha(p.color, alpha * 2 / 3));
                g.fill(x, y, x + 1, y + 1, ConduitUi.withAlpha(0xFFFFFFFF, alpha));
            }
        }
    }
}
