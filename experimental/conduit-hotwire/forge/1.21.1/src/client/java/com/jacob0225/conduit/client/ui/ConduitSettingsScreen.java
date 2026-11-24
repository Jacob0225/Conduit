package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.config.ClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Conduit's settings, in three tabs. Opened from Mod Menu or the title-screen
 * gear; when opened from the gear it continues the gear's zoom: the big gear
 * flies into the header while the panel springs open around it.
 */
public class ConduitSettingsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Settings");
    private static final String[] TABS = {"General", "Sounds", "Visuals"};
    private static final long INTRO_MS = 420L;
    private static final long POP_MS = 220L;
    private static final long SLIDE_MS = 200L;

    private interface Setter {
        void set(boolean value);
    }

    private final Screen parent;
    private final ClientConfig config;
    private final boolean fromGear;
    private final float gearAngle;
    private static int lastTab = 0;

    private int tab = lastTab;
    private String footer = "";
    private int footerColor = ConduitUi.MUTED;
    private long footerAt;
    private long openedAt = -1L;
    private boolean introBurstDone;
    private long tabChangedAt = -10_000L;
    private int tabDirection = 1;
    private float indicatorX = -1;
    private float indicatorW;
    private long lastFrame;
    private final List<AbstractWidget> content = new ArrayList<>();
    private final List<Integer> contentBaseX = new ArrayList<>();
    private final List<TabButton> tabButtons = new ArrayList<>();

    public ConduitSettingsScreen(Screen parent) {
        this(parent, false, 0.0F);
    }

    public ConduitSettingsScreen(Screen parent, boolean fromGear, float gearAngle) {
        super(Component.literal("Conduit Settings"));
        this.parent = parent;
        this.config = ClientConfig.get();
        this.fromGear = fromGear && config.animations();
        this.gearAngle = gearAngle;
    }

    private int panelWidth() { return Math.min(this.width - 16, 360); }
    private int rowHeight() { return this.height < 300 ? 20 : 28; }
    private int panelHeight() { return Math.min(this.height - 16, 74 + 4 * (rowHeight() + 4) + 50); }
    private int left() { return this.width / 2 - panelWidth() / 2; }
    private int top() { return this.height / 2 - panelHeight() / 2; }

    private boolean intro() {
        return fromGear && openedAt >= 0 && ConduitUi.now() - openedAt < INTRO_MS;
    }

    @Override
    protected void init() {
        if (openedAt < 0) {
            openedAt = ConduitUi.now();
            if (!fromGear) ConduitSounds.open();
        }
        content.clear();
        contentBaseX.clear();
        tabButtons.clear();

        int l = left();
        int t = top();
        int w = panelWidth() - 24;

        // Tab bar
        int tabW = Math.min(90, w / TABS.length);
        int tabsX = this.width / 2 - tabW * TABS.length / 2;
        for (int i = 0; i < TABS.length; i++) {
            TabButton b = new TabButton(tabsX + i * tabW, t + 50, tabW, 16, i);
            tabButtons.add(addRenderableWidget(b));
        }

        int x = l + 12;
        int y = t + 74;
        int h = rowHeight();
        switch (tab) {
            case 0 -> {
                y = toggle(x, y, w, h, "Auto-start downloads", "Begin downloading as soon as the screen opens.",
                        config.autoStartDownloads(), config::setAutoStartDownloads);
                y = toggle(x, y, w, h, "Show optional mods", "Count a server's optional mods in the summary.",
                        config.showOptionalMods(), config::setShowOptionalMods);
                y = toggle(x, y, w, h, "Compact download UI", "Smaller install panel and list rows.",
                        config.compactDownloadUi(), config::setCompactDownloadUi);
                toggle(x, y, w, h, "Title-screen gear", "Show the Conduit gear on the main menu.",
                        config.titleScreenButton(), config::setTitleScreenButton);
            }
            case 1 -> {
                y = toggle(x, y, w, h, "UI sounds", "Clicks, chimes and progress sounds.",
                        config.uiSounds(), config::setUiSounds);
                add(new ConduitSlider(x, y, w, h, "Volume", config.soundVolume(),
                        config::setSoundVolume, () -> save("Volume " + config.soundVolume() + "%.")));
                y += h + 4;
                y = toggle(x, y, w, h, "Hover sounds", "A soft tick when the pointer lands on a control.",
                        config.hoverSounds(), config::setHoverSounds);
                add(new ConduitButton(x, y + Math.max(0, (h - 20) / 2), w, 20, Component.literal("Play test sound"),
                        ConduitButton.Style.SECONDARY, btn -> ConduitSounds.preview()));
            }
            default -> {
                add(new ConduitThemePicker(x, y, w, h, config.theme(), theme -> {
                    config.setTheme(theme);
                    ConduitUi.applyTheme(theme);
                    save(ConduitUi.themeLabel(theme) + " theme.");
                }));
                y += h + 4;
                y = toggle(x, y, w, h, "Particles", "Sparkles and bursts in Conduit screens.",
                        config.particles(), config::setParticles);
                y = toggle(x, y, w, h, "Animations", "Spinning, bobbing and zooming effects.",
                        config.animations(), config::setAnimations);
                add(new ConduitButton(x, y + Math.max(0, (h - 20) / 2), w, 20, Component.literal("Preview particles"),
                        ConduitButton.Style.SECONDARY, btn -> {
                            ConduitParticles.shower(left() + 20, left() + panelWidth() - 20, btn.getY(), ConduitUi.ACCENT, 50);
                            ConduitParticles.bubbles(this.width / 2.0F, btn.getY(), 12);
                        }));
            }
        }

        int b = t + panelHeight();
        int bw = Math.min(140, (panelWidth() - 30) / 2);
        addRenderableWidget(new ConduitButton(this.width / 2 - bw - 3, b - 30, bw, 20, Component.literal("Reset"),
                ConduitButton.Style.SECONDARY, btn -> resetDefaults())
                .tooltip("Put every Conduit setting back to its default."));
        addRenderableWidget(new ConduitButton(this.width / 2 + 3, b - 30, bw, 20, Component.literal("Done"),
                ConduitButton.Style.PRIMARY, btn -> close()));
    }

    private void add(AbstractWidget widget) {
        content.add(addRenderableWidget(widget));
        contentBaseX.add(widget.getX());
    }

    private int toggle(int x, int y, int w, int h, String label, String description, boolean value, Setter setter) {
        add(new ConduitToggle(x, y, w, h, label, h >= 26 ? description : null, value,
                (toggle, next) -> {
                    setter.set(next);
                    save(label + (next ? " on." : " off."));
                }));
        return y + h + 4;
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tabDirection = index > tab ? 1 : -1;
        tab = index;
        lastTab = index;
        tabChangedAt = ConduitUi.now();
        ConduitSounds.tab(index);
        rebuildWidgets();
    }

    private void resetDefaults() {
        config.resetToDefaults();
        ConduitUi.applyTheme(config.theme());
        save("Settings reset to defaults.");
        ConduitParticles.burst(this.width / 2.0F, top() + panelHeight() - 20, ConduitUi.ACCENT, 24, 100.0F);
        rebuildWidgets();
    }

    private void close() {
        ConduitSounds.close();
        ConduitParticles.clear();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        close();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (intro()) return true; // the panel is still zooming in
        if (ConduitUi.inEmblem(mouseX, mouseY, left() + 26, top() + 24, 22)) {
            ConduitUi.kickEmblem();
            ConduitSounds.emblem();
            ConduitParticles.burst(left() + 26, top() + 24, ConduitUi.ACCENT, 22, 110.0F);
            ConduitParticles.bubbles(left() + 26, top() + 30, 6);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // Drawn at the start of render() instead.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        ConduitUi.backdrop(g, this.width, this.height);
        long now = ConduitUi.now();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        int l = left();
        int t = top();
        int r = l + panelWidth();
        int b = t + panelHeight();

        // Opening motion: springy scale around the centre of the screen.
        float age = now - openedAt;
        float scale = 1.0F;
        boolean animate = config.animations();
        if (fromGear && age < INTRO_MS) {
            scale = 0.35F + 0.65F * ConduitUi.easeOutBack(age / INTRO_MS);
        } else if (animate && age < POP_MS) {
            scale = 0.92F + 0.08F * ConduitUi.easeOutBack(age / POP_MS);
        }
        boolean scaled = scale != 1.0F;
        if (scaled) {
            g.pose().pushPose();
            g.pose().translate(this.width / 2.0F, this.height / 2.0F, 0.0F);
            g.pose().scale(scale, scale, 1.0F);
            g.pose().translate(-this.width / 2.0F, -this.height / 2.0F, 0.0F);
        }

        // Tab content slides in from the direction of the new tab.
        float slide = animate ? 1.0F - ConduitUi.easeOutCubic((now - tabChangedAt) / (float) SLIDE_MS) : 0.0F;
        int offset = Math.round(slide * 16.0F * tabDirection);
        for (int i = 0; i < content.size(); i++) content.get(i).setX(contentBaseX.get(i) + offset);

        ConduitUi.panel(g, l, t, r, b);
        if (!intro()) ConduitUi.emblem(g, l + 26, t + 24, 22, false, ConduitUi.ACCENT);
        ConduitUi.text(g, Component.literal("Conduit Settings").withStyle(ChatFormatting.BOLD), l + 46, t + 12,
                ConduitUi.TEXT);
        ConduitUi.text(g, "config/conduit/client.json", l + 46, t + 25, ConduitUi.DIM);
        ConduitUi.pill(g, ConduitUi.themeLabel(config.theme()).toUpperCase(java.util.Locale.ROOT), r - 10, t + 11,
                ConduitUi.ACCENT, true);
        ConduitUi.divider(g, l + 10, r - 10, t + 44);

        // Sliding underline under the active tab.
        TabButton active = tabButtons.isEmpty() ? null : tabButtons.get(tab);
        if (active != null) {
            float tx = active.getX() + 10;
            float tw = active.getWidth() - 20;
            if (indicatorX < 0 || !animate) { indicatorX = tx; indicatorW = tw; }
            indicatorX += (tx - indicatorX) * Math.min(1.0F, dt * 14.0F);
            indicatorW += (tw - indicatorW) * Math.min(1.0F, dt * 14.0F);
            g.fill(l + 12, t + 67, r - 12, t + 68, 0xFF16293A);
            ConduitUi.horizontalGradient(g, Math.round(indicatorX), t + 66, Math.round(indicatorX + indicatorW), t + 68,
                    ConduitUi.ACCENT, ConduitUi.ACCENT_2);
        }

        if (!footer.isEmpty()) {
            float fade = (now - footerAt) / 2500.0F;
            int alpha = fade < 0.7F ? 0xFF : (int) (0xFF * Math.max(0.0F, 1.0F - (fade - 0.7F) / 0.3F));
            if (alpha > 8) {
                int w = ConduitUi.font().width(footer) + 12;
                int x = this.width / 2 - w / 2;
                int y = b - 44;
                ConduitUi.icon(g, footerColor == ConduitUi.RED ? ConduitUi.Icon.WARN : ConduitUi.Icon.CHECK,
                        x, y, ConduitUi.withAlpha(footerColor, alpha));
                ConduitUi.text(g, footer, x + 12, y, ConduitUi.withAlpha(footerColor, alpha));
            }
        }

        super.render(g, mx, my, delta);
        if (scaled) g.pose().popPose();

        // The gear handed over from the title screen flies into the header.
        if (fromGear && age < INTRO_MS) {
            float k = ConduitUi.easeInOutCubic(age / INTRO_MS);
            float startX = this.width / 2.0F;
            float startY = this.height / 2.0F;
            float endX = this.width / 2.0F + (l + 26 - this.width / 2.0F) * scale;
            float endY = this.height / 2.0F + (t + 24 - this.height / 2.0F) * scale;
            float size = GearButton.HANDOFF_SIZE + (22.0F - GearButton.HANDOFF_SIZE) * k;
            float spin = gearAngle + (1.0F - k) * 3.0F + k * 9.0F;
            ConduitUi.gear(g, startX + (endX - startX) * k, startY + (endY - startY) * k, size, spin);
        } else if (fromGear && !introBurstDone) {
            introBurstDone = true;
            ConduitParticles.burst(l + 26, t + 24, ConduitUi.ACCENT, 20, 95.0F);
            ConduitParticles.bubbles(l + 26, t + 30, 5);
        }
        ConduitParticles.render(g);
    }

    private void save(String message) {
        try {
            config.save();
            footer = message + " Saved.";
            footerColor = ConduitUi.GREEN;
        } catch (IOException e) {
            LOGGER.warn("Failed to save Conduit client config: {}", e.getMessage());
            footer = "Could not save config.";
            footerColor = ConduitUi.RED;
            ConduitSounds.failure();
        }
        footerAt = ConduitUi.now();
    }

    /** One tab in the bar; the screen draws the sliding underline. */
    private final class TabButton extends AbstractButton {
        private final int index;
        private boolean wasHovered;

        TabButton(int x, int y, int w, int h, int index) {
            super(x, y, w, h, Component.literal(TABS[index]));
            this.index = index;
        }

        @Override
        public void onPress() {
            selectTab(index);
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            // selectTab plays the tab sound
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            boolean hot = active && isHovered();
            if (hot && !wasHovered && index != tab) ConduitSounds.hover();
            wasHovered = hot;
            boolean selected = index == tab;
            if (isHoveredOrFocused() && !selected) {
                ConduitUi.roundRect(g, getX() + 4, getY(), getX() + getWidth() - 4, getY() + getHeight(), 0x30FFFFFF);
            }
            int color = selected ? ConduitUi.ACCENT : isHoveredOrFocused() ? ConduitUi.TEXT : ConduitUi.MUTED;
            ConduitUi.centered(g, TABS[index], getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
