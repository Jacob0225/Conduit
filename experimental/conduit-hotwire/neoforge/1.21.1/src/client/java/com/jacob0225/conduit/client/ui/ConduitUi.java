package com.jacob0225.conduit.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.Util;

/**
 * Shared look for every Conduit screen: an ocean-dark backdrop, framed panels
 * with a prismarine accent, pixel icons, pills and progress bars.
 *
 * <p>All version-specific drawing calls live here so the screens themselves
 * stay nearly identical across Minecraft versions.
 */
public final class ConduitUi {

    // ── Palette (ARGB) ───────────────────────────────────────────────────────
    public static final int BG_TOP = 0xFF06101A;
    public static final int BG_BOTTOM = 0xFF0B2030;
    public static final int PANEL = 0xF20E1925;
    public static final int PANEL_EDGE = 0xFF1F3A50;
    public static final int PANEL_SHINE = 0xFF2B5370;
    public static final int ROW = 0xFF132230;
    public static final int ROW_HOVER = 0xFF1A3043;
    // Accent colours follow the theme chosen in settings (see applyTheme).
    public static int ACCENT = 0xFF35B9E8;
    public static int ACCENT_2 = 0xFF3FE0C5;
    public static int ACCENT_DEEP = 0xFF1B6F95;
    public static final int TEXT = 0xFFEAF6FF;
    public static final int MUTED = 0xFF8FA8BB;
    public static final int DIM = 0xFF587085;
    public static final int GREEN = 0xFF5BE38C;
    public static final int YELLOW = 0xFFFFD866;
    public static final int RED = 0xFFFF6B6B;
    public static final int GOLD = 0xFFFFB547;

    public static final ResourceLocation CONDUIT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("conduit", "textures/gui/conduit.png");
    public static final ResourceLocation GEAR_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("conduit", "textures/gui/gear.png");

    /** Theme name → {accent, second accent, deep accent}. Order matches ClientConfig.THEMES. */
    public static int[] themeColors(String theme) {
        return switch (theme) {
            case "prismarine" -> new int[]{0xFF3FE0C5, 0xFF8CF5A8, 0xFF16806F};
            case "amethyst" -> new int[]{0xFFB58CFF, 0xFFEB8CFF, 0xFF5B3E9E};
            case "sunset" -> new int[]{0xFFFFB547, 0xFFFF6B9A, 0xFF9A5A1E};
            default -> new int[]{0xFF35B9E8, 0xFF3FE0C5, 0xFF1B6F95};
        };
    }

    public static String themeLabel(String theme) {
        return switch (theme) {
            case "prismarine" -> "Prismarine";
            case "amethyst" -> "Amethyst";
            case "sunset" -> "Sunset";
            default -> "Ocean";
        };
    }

    public static void applyTheme(String theme) {
        int[] c = themeColors(theme);
        ACCENT = c[0];
        ACCENT_2 = c[1];
        ACCENT_DEEP = c[2];
    }

    public static boolean animations() {
        return com.jacob0225.conduit.client.config.ClientConfig.get().animations();
    }

    // ── Easing ───────────────────────────────────────────────────────────────

    public static float clamp01(float t) {
        return Math.max(0.0F, Math.min(1.0F, t));
    }

    public static float easeOutCubic(float t) {
        t = clamp01(t);
        float u = 1.0F - t;
        return 1.0F - u * u * u;
    }

    public static float easeInOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3) / 2.0F;
    }

    /** Overshoots slightly before settling: a springy "pop". */
    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        return 1.0F + c3 * (float) Math.pow(t - 1.0F, 3) + c1 * (float) Math.pow(t - 1.0F, 2);
    }

    public enum Icon { CHECK, CROSS, DOT, RING, PLUS, MINUS, ARROW, WARN, INFO }

    private static final String[] CHECK = {
            "......#", ".....##", "#...##.", "##.##..", ".###...", "..#....", "......."};
    private static final String[] CROSS = {
            "##...##", ".##.##.", "..###..", "...#...", "..###..", ".##.##.", "##...##"};
    private static final String[] DOT = {
            ".......", "..###..", ".#####.", ".#####.", ".#####.", "..###..", "......."};
    private static final String[] RING = {
            ".......", "..###..", ".#...#.", ".#...#.", ".#...#.", "..###..", "......."};
    private static final String[] PLUS = {
            "...#...", "...#...", "...#...", "#######", "...#...", "...#...", "...#..."};
    private static final String[] MINUS = {
            ".......", ".......", ".......", "#######", ".......", ".......", "......."};
    private static final String[] ARROW = {
            "...#...", "....#..", ".....#.", "#######", ".....#.", "....#..", "...#..."};
    private static final String[] WARN = {
            "...#...", "..###..", "..#.#..", ".##.##.", ".#####.", "##.#.##", "#######"};
    private static final String[] INFO = {
            "...#...", ".......", "..##...", "...#...", "...#...", "...#...", "..###.."};

    private ConduitUi() {}

    public static long now() {
        return Util.getMillis();
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    // ── Backdrop & panels ────────────────────────────────────────────────────

    /** Opaque deep-water gradient with slow rising bubbles. */
    public static void backdrop(GuiGraphics g, int width, int height) {
        backdrop(g, width, height, 1.0F);
    }

    /** Backdrop at {@code opacity} (0–1), used by the title-screen zoom transition. */
    public static void backdrop(GuiGraphics g, int width, int height, float opacity) {
        int a = Math.round(clamp01(opacity) * 255);
        g.fillGradient(0, 0, width, height, withAlpha(BG_TOP, a), withAlpha(BG_BOTTOM, a));
        long t = animations() ? now() : 0L;
        for (int i = 0; i < 26; i++) {
            int seed = i * 7919 + 104729;
            int x = Math.floorMod(seed * 31, Math.max(1, width));
            int speed = 9 + Math.floorMod(seed, 17);           // px per second
            int span = height + 24;
            int y = height + 12 - (int) Math.floorMod((t * speed / 1000L) + seed, span);
            int sway = (int) Math.round(Math.sin((t / 900.0D) + i) * 2.0D);
            int size = 1 + Math.floorMod(seed / 7, 3);
            int alpha = (0x18 + Math.floorMod(seed / 3, 0x22)) * a / 255;
            g.fill(x + sway, y, x + sway + size, y + size, (alpha << 24) | 0x7FD8F0);
        }
        // Soft vignette at top and bottom keeps attention on the panel.
        g.fillGradient(0, 0, width, 40, withAlpha(0, 0x66 * a / 255), 0x00000000);
        g.fillGradient(0, height - 40, width, height, 0x00000000, withAlpha(0, 0x66 * a / 255));
    }

    /** Framed panel with a drop shadow and a prismarine accent along the top. */
    public static void panel(GuiGraphics g, int l, int t, int r, int b) {
        roundRect(g, l + 3, t + 4, r + 3, b + 4, 0x66000000);
        roundRect(g, l - 1, t - 1, r + 1, b + 1, PANEL_EDGE);
        roundRect(g, l, t, r, b, PANEL);
        g.fillGradient(l + 1, t + 2, r - 1, t + 26, 0x2235B9E8, 0x0035B9E8);
        horizontalGradient(g, l + 1, t, r - 1, t + 2, ACCENT, ACCENT_2);
        g.fill(l + 1, t + 2, r - 1, t + 3, withAlpha(PANEL_SHINE, 0x50));
    }

    /** Rectangle with 1px clipped corners, which reads as rounded at GUI scale. */
    public static void roundRect(GuiGraphics g, int l, int t, int r, int b, int color) {
        if (r - l < 3 || b - t < 3) {
            g.fill(l, t, r, b, color);
            return;
        }
        g.fill(l + 1, t, r - 1, t + 1, color);
        g.fill(l, t + 1, r, b - 1, color);
        g.fill(l + 1, b - 1, r - 1, b, color);
    }

    public static void roundOutline(GuiGraphics g, int l, int t, int r, int b, int color) {
        g.fill(l + 1, t, r - 1, t + 1, color);
        g.fill(l + 1, b - 1, r - 1, b, color);
        g.fill(l, t + 1, l + 1, b - 1, color);
        g.fill(r - 1, t + 1, r, b - 1, color);
    }

    public static void horizontalGradient(GuiGraphics g, int l, int t, int r, int b, int from, int to) {
        int w = r - l;
        if (w <= 0) return;
        int steps = Math.min(w, 24);
        for (int i = 0; i < steps; i++) {
            int x0 = l + w * i / steps;
            int x1 = l + w * (i + 1) / steps;
            g.fill(x0, t, x1, b, lerpColor(from, to, steps == 1 ? 0.0F : i / (float) (steps - 1)));
        }
    }

    public static void divider(GuiGraphics g, int l, int r, int y) {
        horizontalGradient(g, l, y, (l + r) / 2, y + 1, 0x001F3A50, PANEL_EDGE);
        horizontalGradient(g, (l + r) / 2, y, r, y + 1, PANEL_EDGE, 0x001F3A50);
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    public static void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, true);
    }

    public static void text(GuiGraphics g, Component s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, true);
    }

    public static void text(GuiGraphics g, FormattedCharSequence s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, true);
    }

    public static void centered(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(font(), s, cx - font().width(s) / 2, y, color, true);
    }

    public static void centered(GuiGraphics g, Component s, int cx, int y, int color) {
        g.drawString(font(), s, cx - font().width(s) / 2, y, color, true);
    }

    public static String trim(String s, int maxWidth) {
        Font font = font();
        if (s == null) return "";
        if (font.width(s) <= maxWidth) return s;
        String suffix = "...";
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + suffix) > maxWidth) end--;
        return end <= 0 ? suffix : s.substring(0, end) + suffix;
    }

    /** A small rounded label; returns its width. Right-aligned when {@code alignRight}. */
    public static int pill(GuiGraphics g, String s, int x, int y, int fg, boolean alignRight) {
        int w = font().width(s) + 8;
        int left = alignRight ? x - w : x;
        roundRect(g, left, y, left + w, y + 11, (fg & 0x00FFFFFF) | 0x26000000);
        roundOutline(g, left, y, left + w, y + 11, (fg & 0x00FFFFFF) | 0x66000000);
        g.drawString(font(), s, left + 4, y + 2, fg, false);
        return w;
    }

    public static void tooltip(GuiGraphics g, Component s, int mx, int my) {
        // Deferred so the tooltip draws above the screen's buttons.
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) screen.setTooltipForNextRenderPass(s);
    }

    public static void tooltip(GuiGraphics g, java.util.List<FormattedCharSequence> lines, int mx, int my) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null) screen.setTooltipForNextRenderPass(lines);
    }

    // ── Icons & widgets ──────────────────────────────────────────────────────

    /** 7×7 pixel icon with its top-left at (x, y). */
    public static void icon(GuiGraphics g, Icon icon, int x, int y, int color) {
        String[] rows = switch (icon) {
            case CHECK -> CHECK;
            case CROSS -> CROSS;
            case DOT -> DOT;
            case RING -> RING;
            case PLUS -> PLUS;
            case MINUS -> MINUS;
            case ARROW -> ARROW;
            case WARN -> WARN;
            case INFO -> INFO;
        };
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int run = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean on = col < line.length() && line.charAt(col) == '#';
                if (on && run < 0) run = col;
                if (!on && run >= 0) {
                    g.fill(x + run, y + row, x + col, y + row + 1, color);
                    run = -1;
                }
            }
        }
    }

    /** Four orbiting dots in a 7×7 box; brightness chases around the ring. */
    /** Emblem hit-box used by screens for the click easter egg (centre, half-size). */
    public static boolean inEmblem(double mx, double my, int cx, int cy, int size) {
        int r = size / 2 + 5;
        return mx >= cx - r && mx < cx + r && my >= cy - r && my < cy + r;
    }

    public static void spinner(GuiGraphics g, int x, int y, int color) {
        int[][] spots = {{3, 0}, {5, 1}, {6, 3}, {5, 5}, {3, 6}, {1, 5}, {0, 3}, {1, 1}};
        int head = (int) ((now() / 90L) % spots.length);
        for (int i = 0; i < spots.length; i++) {
            int age = Math.floorMod(head - i, spots.length);
            int alpha = Math.max(0x20, 0xFF - age * 0x30);
            int c = (alpha << 24) | (color & 0x00FFFFFF);
            g.fill(x + spots[i][0], y + spots[i][1], x + spots[i][0] + 1, y + spots[i][1] + 1, c);
        }
    }

    /** Rounded progress bar; {@code shimmer} sweeps a highlight while work is running. */
    public static void progressBar(GuiGraphics g, int l, int t, int r, float ratio,
                                   int color, boolean shimmer, float glow) {
        int h = 7;
        roundRect(g, l, t, r, t + h, 0xFF081019);
        roundOutline(g, l, t, r, t + h, 0xFF1C3346);
        int inner = r - l - 2;
        int fill = Math.round(inner * Math.max(0.0F, Math.min(1.0F, ratio)));
        if (fill > 0) {
            int fr = l + 1 + fill;
            int deep = lerpColor(color, 0xFF000000, 0.35F);
            g.fillGradient(l + 1, t + 1, fr, t + h - 1, color, deep);
            g.fill(l + 1, t + 1, fr, t + 2, lerpColor(color, 0xFFFFFFFF, 0.45F));
            if (shimmer && fill > 6) {
                int sweep = 28;
                int pos = (int) ((now() / 6L) % (fill + sweep)) - sweep;
                for (int i = 0; i < sweep; i++) {
                    int x = l + 1 + pos + i;
                    if (x < l + 1 || x >= fr) continue;
                    float k = 1.0F - Math.abs(i - sweep / 2.0F) / (sweep / 2.0F);
                    int a = (int) (k * 0x55);
                    g.fill(x, t + 1, x + 1, t + h - 1, (a << 24) | 0xFFFFFF);
                }
            }
        }
        if (glow > 0.0F) {
            int a = (int) (Math.max(0.0F, Math.min(1.0F, glow)) * 0x70);
            int c = (a << 24) | (color & 0x00FFFFFF);
            roundOutline(g, l - 1, t - 1, r + 1, t + h + 1, c);
            roundOutline(g, l - 2, t - 2, r + 2, t + h + 2, (a / 2 << 24) | (color & 0x00FFFFFF));
        }
    }

    /** The Conduit block icon, centred at (cx, cy), optionally rotated (radians). */
    public static void conduit(GuiGraphics g, int cx, int cy, int size, float angle) {
        int half = size / 2;
        int halfH = Math.round(size * 1.1F) / 2;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0F);
        if (angle != 0.0F) g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(angle));
        g.blit(CONDUIT_TEXTURE, -half, -halfH, half * 2, halfH * 2, 0.0F, 0.0F, 100, 110, 100, 110);
        g.pose().popPose();
    }

    /** The blue gear, centred at (cx, cy), rotated by {@code angle} radians. */
    public static void gear(GuiGraphics g, float cx, float cy, float size, float angle) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0F);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(angle));
        g.pose().scale(size / 64.0F, size / 64.0F, 1.0F);
        g.blit(GEAR_TEXTURE, -32, -32, 64, 64, 0.0F, 0.0F, 128, 128, 128, 128);
        g.pose().popPose();
    }

    /** Header emblem: soft pulsing glow, gentle bob when idle, spin while working. */
    public static void emblem(GuiGraphics g, int cx, int cy, int size, boolean working, int glowColor) {
        long t = animations() ? now() : 0L;
        float pulse = 0.5F + 0.5F * (float) Math.sin(t / (working ? 180.0D : 600.0D));
        int r = size / 2 + 5;
        int a1 = (int) (0x18 + pulse * 0x20);
        roundRect(g, cx - r, cy - r, cx + r, cy + r, (a1 << 24) | (glowColor & 0x00FFFFFF));
        roundRect(g, cx - r + 3, cy - r + 3, cx + r - 3, cy + r - 3, ((a1 + 0x10) << 24) | (glowColor & 0x00FFFFFF));
        float angle = working ? (t % 2400L) * ((float) Math.PI * 2.0F) / 2400.0F : 0.0F;
        int bob = working ? 0 : (int) Math.round(Math.sin(t / 700.0D) * 1.2D);
        long kick = now() - emblemKickAt;
        int drawSize = size;
        if (kick >= 0 && kick < 800L) {
            // Clicked: one fast, eased full turn with a little "boing".
            float k = kick / 800.0F;
            angle += (float) (Math.PI * 2.0) * easeOutCubic(k);
            drawSize = Math.round(size * (1.0F + 0.25F * (float) Math.sin(k * Math.PI) * (1.0F - k)));
        }
        conduit(g, cx, cy + bob, drawSize, angle);
    }

    private static long emblemKickAt = -10_000L;

    /** Make every emblem do its click animation (screens call this on an emblem click). */
    public static void kickEmblem() {
        emblemKickAt = now();
    }

    // ── Colour helpers ───────────────────────────────────────────────────────

    public static int lerpColor(int a, int b, float k) {
        k = Math.max(0.0F, Math.min(1.0F, k));
        int aa = a >>> 24, ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16)
                | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }

    public static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
