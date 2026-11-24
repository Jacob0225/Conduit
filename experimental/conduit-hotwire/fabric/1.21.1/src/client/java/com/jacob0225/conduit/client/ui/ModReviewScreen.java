package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.compat.HotwireBridge;
import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.DependencyResolver;
import com.jacob0225.conduit.client.download.InstalledModIndex;
import com.jacob0225.conduit.client.download.ModDownloadManager;
import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.download.ModrinthClient;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.util.InstanceRestarter;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Install screen shown when a join is intercepted and the client is missing
 * required mods (or, with Hotwire, when the running mod set must switch).
 */
public class ModReviewScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/UI");

    private enum Phase { PROMPT, DOWNLOADING, LIVE, DONE, ERROR }

    private enum RowState {
        MISSING("MISSING", ConduitUi.GOLD),
        QUEUED("QUEUED", ConduitUi.DIM),
        DOWNLOADING("DOWNLOADING", ConduitUi.ACCENT),
        DONE("VERIFIED", ConduitUi.GREEN),
        READY("READY", ConduitUi.GREEN),
        FAILED("FAILED", ConduitUi.RED);

        final String label;
        final int color;

        RowState(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    private final ClientConfig config = ClientConfig.get();
    private final ManifestPayload payload;
    private final ServerAddress serverAddress;
    private final ServerData serverData;
    private final Screen parent;
    private final List<ModEntry> requiredMods;
    private final int optionalCount;
    private final RowState[] rowState;
    private final String[] rowReason;

    private boolean hotwireAttempted;
    private boolean hotwireApplied;
    /** Jars just installed for this server; Hotwire is offered only for a fresh install. */
    private List<java.nio.file.Path> hotwireJars = List.of();
    private boolean hotwireEligible = true;

    private Phase phase = Phase.PROMPT;
    private ConduitButton primaryButton;
    private ConduitButton skipButton;
    private ConduitButton secondaryButton;
    private String footerText = "";
    private int footerColor = ConduitUi.MUTED;
    private ConduitUi.Icon footerIcon = ConduitUi.Icon.INFO;
    private int finishedCount = 0;
    private int failedCount = 0;
    private boolean autoStarted = false;
    private boolean openSoundPlayed = false;

    private int scrollOffset = 0;
    private int listTop = 0;
    private int listBottom = 0;
    private int listLeft = 0;
    private int listRight = 0;
    private int visibleModRows = 0;
    private int rowH = 20;
    private float[] rowHover = new float[0];
    private int hoveredRow = -1;

    private float shownRatio = 0.0F;
    private long lastFrame = 0L;
    private long finishedAt = 0L;

    public ModReviewScreen(ManifestPayload payload,
                           ServerAddress address, ServerData serverData, Screen parent) {
        super(Component.literal("Conduit - Mod Sync"));
        this.payload = payload;
        this.serverAddress = address;
        this.serverData = serverData;
        this.parent = parent;
        this.requiredMods = payload.mods().stream().filter(ModEntry::required).toList();
        this.optionalCount = (int) payload.mods().stream().filter(mod -> !mod.required()).count();

        this.rowState = new RowState[requiredMods.size()];
        this.rowReason = new String[requiredMods.size()];
        this.rowHover = new float[requiredMods.size()];
        Arrays.fill(rowState, phase == Phase.DONE ? RowState.READY : RowState.MISSING);
        if (phase == Phase.DONE) {
            finishedCount = requiredMods.size();
            shownRatio = 1.0F;
            setFooter(hotwireReady()
                    ? "Already downloaded. Switch the running game to this server's mods."
                    : "Already downloaded. " + reloadHint("this server's mods"), ConduitUi.MUTED, ConduitUi.Icon.INFO);
        } else if (requiredMods.isEmpty()) {
            setFooter("No required mods are missing.", ConduitUi.GREEN, ConduitUi.Icon.CHECK);
        } else {
            setFooter(hotwireReady()
                    ? "Download to join. Hotwire can load supported mods without restarting."
                    : "Download the missing mods. " + reloadHint("them"), ConduitUi.MUTED, ConduitUi.Icon.INFO);
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private boolean compact() {
        return config.compactDownloadUi();
    }

    private int panelWidth() {
        return Math.min(this.width - 16, compact() ? 360 : 440);
    }

    private int panelHeight() {
        return Math.min(this.height - 16, compact() ? 220 : 256);
    }

    private int panelLeft() {
        return this.width / 2 - panelWidth() / 2;
    }

    private int panelRight() {
        return panelLeft() + panelWidth();
    }

    private int panelTop() {
        return this.height / 2 - panelHeight() / 2;
    }

    private int panelBottom() {
        return panelTop() + panelHeight();
    }

    private boolean narrow() {
        return panelWidth() < 340;
    }

    @Override
    protected void init() {
        primaryButton = new ConduitButton(0, 0, 100, 20, Component.literal("Download"),
                ConduitButton.Style.PRIMARY, btn -> onPrimary());
        skipButton = new ConduitButton(0, 0, 100, 20, Component.literal("Join anyway"),
                ConduitButton.Style.WARNING, btn -> skipAndJoin())
                .tooltip("Join without downloading. The server may disconnect you.");
        secondaryButton = new ConduitButton(0, 0, 100, 20, Component.literal("Back"),
                ConduitButton.Style.SECONDARY, btn -> backToParent());

        addRenderableWidget(primaryButton);
        addRenderableWidget(skipButton);
        addRenderableWidget(secondaryButton);
        applyPhaseButtons();

        if (!openSoundPlayed) {
            openSoundPlayed = true;
            ConduitSounds.open();
        }
        if (phase == Phase.PROMPT && config.autoStartDownloads() && !autoStarted && !requiredMods.isEmpty()) {
            autoStarted = true;
            startDownload();
        }
    }

    private void onPrimary() {
        switch (phase) {
            case PROMPT, ERROR -> startDownload();
            case DONE -> finishInstallation();
            default -> { }
        }
    }

    private boolean hotwireReady() {
        return !hotwireAttempted && hotwireEligible && HotwireBridge.available();
    }

    /** "Restart to load X." or, where Conduit can only close the game, "Reopen the game to load X." */
    private static String reloadHint(String what) {
        return (InstanceRestarter.canRelaunch() ? "Restart" : "Reopen the game") + " to load " + what + ".";
    }

    private String restartLabel() {
        return InstanceRestarter.canRelaunch() ? "Restart game" : "Close game";
    }

    private void applyPhaseButtons() {
        if (primaryButton == null) return;
        switch (phase) {
            case PROMPT -> {
                show(primaryButton, requiredMods.isEmpty() ? "Continue" : (narrow() ? "Download"
                        : "Download " + requiredMods.size() + (requiredMods.size() == 1 ? " mod" : " mods")), true);
                primaryButton.style(ConduitButton.Style.PRIMARY)
                        .tooltip(requiredMods.isEmpty() ? null : "Download from Modrinth and verify each file's SHA-512.");
                show(skipButton, narrow() ? "Skip" : "Join anyway", true);
                show(secondaryButton, "Cancel", true);
            }
            case DOWNLOADING, LIVE -> {
                show(primaryButton, phase == Phase.LIVE ? "Loading live..." : "Installing...", false);
                hide(skipButton);
                hide(secondaryButton);
            }
            case DONE -> {
                String label;
                String tip;
                if (hotwireApplied) {
                    label = "Join server";
                    tip = null;
                } else if (hotwireReady() && !hotwireJars.isEmpty()) {
                    label = "Load live";
                    tip = "Use Hotwire to load the new mods into the running game without restarting.";
                } else {
                    label = restartLabel();
                    tip = InstanceRestarter.canRelaunch()
                            ? "Minecraft restarts with this server's mods, then you can reconnect."
                            : "Minecraft closes. Start it again from your launcher, then reconnect.";
                }
                show(primaryButton, label, true);
                primaryButton.style(ConduitButton.Style.PRIMARY).tooltip(tip);
                hide(skipButton);
                show(secondaryButton, "Back", true);
            }
            case ERROR -> {
                show(primaryButton, failedCount > 0 ? "Retry failed" : "Retry", true);
                primaryButton.style(ConduitButton.Style.PRIMARY).tooltip(null);
                show(skipButton, narrow() ? "Skip" : "Join anyway", true);
                show(secondaryButton, "Back", true);
            }
        }
        layoutButtons();
    }

    private static void show(ConduitButton button, String label, boolean active) {
        button.setMessage(Component.literal(label));
        button.visible = true;
        button.active = active;
    }

    private static void hide(ConduitButton button) {
        button.visible = false;
        button.active = false;
    }

    /** Centres whichever buttons are visible along the bottom of the panel. */
    private void layoutButtons() {
        List<ConduitButton> shown = new ArrayList<>();
        for (ConduitButton b : List.of(primaryButton, skipButton, secondaryButton)) if (b.visible) shown.add(b);
        if (shown.isEmpty()) return;
        int gap = 6;
        int inner = panelWidth() - 28;
        int each = Math.min(shown.size() == 1 ? 180 : 132, (inner - gap * (shown.size() - 1)) / shown.size());
        int total = each * shown.size() + gap * (shown.size() - 1);
        int x = this.width / 2 - total / 2;
        int y = panelBottom() - 30;
        for (ConduitButton b : shown) {
            b.place(x, y, each);
            x += each + gap;
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // Drawn at the start of render() instead.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        // 1.21.1 Screen.render paints the background itself, so draw ours first and
        // make renderBackground a no-op (otherwise it would cover the panel).
        ConduitUi.backdrop(g, this.width, this.height);
        long now = ConduitUi.now();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        shownRatio += (progressRatio() - shownRatio) * Math.min(1.0F, dt * 6.0F);

        int l = panelLeft();
        int t = panelTop();
        int r = panelRight();
        int b = panelBottom();
        ConduitUi.panel(g, l, t, r, b);

        renderHeader(g, l, t, r);
        ConduitUi.divider(g, l + 10, r - 10, t + 44);
        renderProgress(g, l, t, r);
        int listTopY = t + (compact() ? 74 : 80);
        renderModRows(g, l + 12, listTopY, r - 12, b - 52, mx, my);
        renderFooter(g, l, r, b);

        super.render(g, mx, my, delta);
        ConduitParticles.render(g);
    }

    private boolean working() {
        return phase == Phase.DOWNLOADING || phase == Phase.LIVE;
    }

    private void renderHeader(GuiGraphics g, int l, int t, int r) {
        int glow = switch (phase) {
            case DONE -> ConduitUi.GREEN;
            case ERROR -> ConduitUi.RED;
            default -> ConduitUi.ACCENT;
        };
        ConduitUi.emblem(g, l + 26, t + 24, 22, working(), glow);

        String title = switch (phase) {
            case PROMPT -> requiredMods.isEmpty() ? "Nothing to install" : "This server needs mods";
            case DOWNLOADING -> "Installing verified mods";
            case LIVE -> "Loading mods live";
            case DONE -> hotwireApplied ? "Ready to join" : "Mods installed";
            case ERROR -> "Some downloads need attention";
        };
        int textLeft = l + 46;
        String statusLabel = switch (phase) {
            case PROMPT -> requiredMods.isEmpty() ? "UP TO DATE" : "SETUP";
            case DOWNLOADING -> "WORKING";
            case LIVE -> "HOTWIRE";
            case DONE -> hotwireApplied ? "READY" : "INSTALLED";
            case ERROR -> "ATTENTION";
        };
        int statusColor = switch (phase) {
            case PROMPT -> requiredMods.isEmpty() ? ConduitUi.GREEN : ConduitUi.GOLD;
            case DOWNLOADING, LIVE -> ConduitUi.ACCENT;
            case DONE -> ConduitUi.GREEN;
            case ERROR -> ConduitUi.RED;
        };
        int pillW = ConduitUi.font().width(statusLabel) + 8;
        boolean pillFits = r - 10 - pillW > textLeft + 120;
        int titleMax = (pillFits ? r - 16 - pillW : r - 10) - textLeft;
        ConduitUi.text(g, Component.literal(ConduitUi.trim(title, titleMax - 6)).withStyle(ChatFormatting.BOLD),
                textLeft, t + 12, ConduitUi.TEXT);
        String server = ConduitUi.trim(payload.serverName(), titleMax - 10);
        g.fill(textLeft, t + 27, textLeft + 3, t + 30, ConduitUi.ACCENT);
        ConduitUi.text(g, server, textLeft + 7, t + 25, ConduitUi.MUTED);
        if (pillFits) ConduitUi.pill(g, statusLabel, r - 10, t + 11, statusColor, true);
    }

    private void renderProgress(GuiGraphics g, int l, int t, int r) {
        int y = t + 52;
        String left;
        if (phase == Phase.PROMPT) {
            left = requiredMods.size() + " required";
            if (config.showOptionalMods() && optionalCount > 0) left += "  +" + optionalCount + " optional";
        } else if (phase == Phase.LIVE) {
            left = "Applying the plan to the running game";
        } else {
            left = finishedCount + " of " + requiredMods.size() + " installed";
            if (failedCount > 0) left += "  ·  " + failedCount + " failed";
        }
        ConduitUi.text(g, left, l + 12, y, failedCount > 0 && phase != Phase.PROMPT ? ConduitUi.RED : ConduitUi.MUTED);
        if (phase != Phase.PROMPT && phase != Phase.LIVE) {
            String pct = Math.round(shownRatio * 100.0F) + "%";
            ConduitUi.text(g, pct, r - 12 - ConduitUi.font().width(pct), y, ConduitUi.TEXT);
        }
        int color = failedCount > 0 ? ConduitUi.RED : (phase == Phase.DONE ? ConduitUi.GREEN : ConduitUi.ACCENT);
        float glow = finishedAt == 0 ? 0.0F : 1.0F - (ConduitUi.now() - finishedAt) / 900.0F;
        float ratio = phase == Phase.PROMPT ? 0.0F : (phase == Phase.LIVE ? 1.0F : shownRatio);
        ConduitUi.progressBar(g, l + 12, y + 11, r - 12, ratio, color, working(), glow);
    }

    private void renderModRows(GuiGraphics g, int left, int top, int right, int bottom, int mx, int my) {
        rowH = compact() ? 16 : 20;
        listLeft = left;
        listRight = right;
        listTop = top;
        listBottom = bottom;
        int maxRows = Math.max(1, (bottom - top) / rowH);
        visibleModRows = maxRows;
        int maxOffset = Math.max(0, requiredMods.size() - maxRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxOffset));

        if (requiredMods.isEmpty()) {
            ConduitUi.roundRect(g, left, top, right, top + rowH, ConduitUi.ROW);
            ConduitUi.icon(g, ConduitUi.Icon.CHECK, left + 8, top + (rowH - 7) / 2, ConduitUi.GREEN);
            ConduitUi.text(g, "Every required mod is already present.", left + 22, top + (rowH - 8) / 2, ConduitUi.MUTED);
            return;
        }

        boolean scrollbar = maxOffset > 0;
        int rowRight = scrollbar ? right - 5 : right;
        int count = Math.min(requiredMods.size() - scrollOffset, maxRows);
        int hovered = -1;
        for (int row = 0; row < count; row++) {
            int i = scrollOffset + row;
            int y = top + row * rowH;
            int yb = y + rowH - 2;
            boolean hot = mx >= left && mx < rowRight && my >= y && my < yb;
            if (hot) hovered = i;
            if (hot && hoveredRow != i) ConduitSounds.hover();
            rowHover[i] += ((hot ? 1.0F : 0.0F) - rowHover[i]) * (config.animations() ? 0.25F : 1.0F);
            int shift = Math.round(rowHover[i] * 3.0F);
            RowState state = rowState[i];
            ConduitUi.roundRect(g, left, y, rowRight, yb,
                    ConduitUi.lerpColor(ConduitUi.ROW, ConduitUi.ROW_HOVER, rowHover[i]));
            g.fill(left, y + 1, left + 2 + shift, yb - 1, ConduitUi.withAlpha(state.color, 0xCC));
            final int rowLeft = left + shift;

            int iconY = y + (rowH - 2 - 7) / 2;
            switch (state) {
                case DOWNLOADING -> ConduitUi.spinner(g, rowLeft + 7, iconY, ConduitUi.ACCENT);
                case DONE, READY -> ConduitUi.icon(g, ConduitUi.Icon.CHECK, rowLeft + 7, iconY, ConduitUi.GREEN);
                case FAILED -> ConduitUi.icon(g, ConduitUi.Icon.CROSS, rowLeft + 7, iconY, ConduitUi.RED);
                case MISSING -> ConduitUi.icon(g, ConduitUi.Icon.ARROW, rowLeft + 7, iconY, ConduitUi.GOLD);
                default -> ConduitUi.icon(g, ConduitUi.Icon.RING, rowLeft + 7, iconY, ConduitUi.DIM);
            }

            ModEntry entry = requiredMods.get(i);
            int textY = y + (rowH - 2 - 8) / 2;
            int pillW = ConduitUi.font().width(state.label) + 8;
            String version = ConduitUi.trim(entry.requiredVersion(), compact() ? 70 : 96);
            int versionW = ConduitUi.font().width(version);
            int nameMax = rowRight - (rowLeft + 22) - pillW - versionW - 20;
            ConduitUi.text(g, ConduitUi.trim(entry.displayName(), Math.max(20, nameMax)), rowLeft + 22, textY,
                    state == RowState.FAILED ? 0xFFFFD0D0 : ConduitUi.TEXT);
            int pillX = rowRight - 6;
            ConduitUi.pill(g, state.label, pillX, y + (rowH - 2 - 11) / 2, state.color, true);
            g.drawString(ConduitUi.font(), version, pillX - pillW - 6 - versionW, textY, ConduitUi.DIM, false);
        }

        if (scrollbar) {
            int trackX = right - 3;
            int trackHeight = Math.max(1, count * rowH - 2);
            int thumbHeight = Math.max(10, trackHeight * maxRows / requiredMods.size());
            int thumbY = top + (trackHeight - thumbHeight) * scrollOffset / maxOffset;
            ConduitUi.roundRect(g, trackX, top, trackX + 3, top + trackHeight, 0xFF0A141E);
            ConduitUi.roundRect(g, trackX, thumbY, trackX + 3, thumbY + thumbHeight, ConduitUi.ACCENT);
        }

        hoveredRow = hovered;
        if (hovered >= 0) renderRowTooltip(g, hovered, mx, my);
    }

    private void renderRowTooltip(GuiGraphics g, int i, int mx, int my) {
        ModEntry entry = requiredMods.get(i);
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.literal(entry.displayName()).withStyle(ChatFormatting.WHITE).getVisualOrderText());
        lines.add(Component.literal(entry.modId() + " " + entry.requiredVersion())
                .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        if (!entry.modrinthProjectId().isEmpty()) {
            lines.add(Component.literal("Modrinth " + entry.modrinthProjectId())
                    .withStyle(ChatFormatting.DARK_AQUA).getVisualOrderText());
        }
        if (rowState[i] == RowState.FAILED && rowReason[i] != null) {
            lines.addAll(ConduitUi.font().split(Component.literal(rowReason[i]).withStyle(ChatFormatting.RED), 220));
        }
        if (!entry.modrinthProjectId().isEmpty()) {
            lines.add(Component.literal("Click to open on Modrinth").withStyle(ChatFormatting.DARK_GRAY,
                    ChatFormatting.ITALIC).getVisualOrderText());
        }
        ConduitUi.tooltip(g, lines, mx, my);
    }

    private void renderFooter(GuiGraphics g, int l, int r, int b) {
        if (footerText.isEmpty()) return;
        int y = b - 46;
        int maxW = r - l - 40;
        String text = ConduitUi.trim(footerText, maxW);
        int w = ConduitUi.font().width(text) + 12;
        int x = this.width / 2 - w / 2;
        if (working()) {
            ConduitUi.spinner(g, x, y, footerColor);
        } else {
            ConduitUi.icon(g, footerIcon, x, y, footerColor);
        }
        ConduitUi.text(g, text, x + 12, y, footerColor);
    }

    private void setFooter(String text, int color, ConduitUi.Icon icon) {
        footerText = text == null ? "" : text;
        footerColor = color;
        footerIcon = icon;
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public void onClose() {
        if (working()) return;
        ConduitSounds.press(ConduitSounds.Press.BACK);
        backToParent();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !working();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (ConduitUi.inEmblem(mouseX, mouseY, panelLeft() + 26, panelTop() + 24, 22)) {
            ConduitUi.kickEmblem();
            ConduitSounds.emblem();
            ConduitParticles.burst(panelLeft() + 26, panelTop() + 24, ConduitUi.ACCENT, 22, 110.0F);
            ConduitParticles.bubbles(panelLeft() + 26, panelTop() + 30, 6);
            return true;
        }
        if (hoveredRow >= 0 && hoveredRow < requiredMods.size()) {
            ModEntry entry = requiredMods.get(hoveredRow);
            if (!entry.modrinthProjectId().isEmpty()) {
                ConduitSounds.press(ConduitSounds.Press.NORMAL);
                // Vanilla link confirmation; the project id is validated as [A-Za-z0-9]{8}.
                ConfirmLinkScreen.confirmLinkNow(this,
                        URI.create("https://modrinth.com/mod/" + entry.modrinthProjectId()));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= listLeft && mouseX < listRight && mouseY >= listTop && mouseY < listBottom
                && requiredMods.size() > visibleModRows && verticalAmount != 0.0D) {
            int maxOffset = requiredMods.size() - visibleModRows;
            int next = Math.max(0, Math.min(maxOffset, scrollOffset + (verticalAmount > 0.0D ? -1 : 1)));
            if (next != scrollOffset) {
                scrollOffset = next;
                ConduitSounds.scroll();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ── Behaviour ────────────────────────────────────────────────────────────

    private float progressRatio() {
        if (requiredMods.isEmpty()) return 1.0F;
        return (float) finishedCount / (float) requiredMods.size();
    }

    private void startDownload() {
        if (requiredMods.isEmpty()) {
            phase = Phase.DONE;
            setFooter("No required downloads. Nothing else to do.", ConduitUi.GREEN,
                    ConduitUi.Icon.CHECK);
            applyPhaseButtons();
            return;
        }

        phase = Phase.DOWNLOADING;
        finishedCount = 0;
        failedCount = 0;
        finishedAt = 0L;
        Arrays.fill(rowState, RowState.QUEUED);
        Arrays.fill(rowReason, null);
        setFooter("Downloading from Modrinth and checking hashes...", ConduitUi.ACCENT, ConduitUi.Icon.INFO);
        applyPhaseButtons();
        ConduitSounds.start();

        String mcVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("1.21.1");

        ModrinthClient modrinth = new ModrinthClient(mcVersion);
        InstalledModIndex index = new InstalledModIndex();
        DependencyResolver deps = new DependencyResolver(index);
        ModDownloadManager manager = new ModDownloadManager(modrinth, deps);

        CompletableFuture.runAsync(() -> {
            try {
                manager.prepare();

                ModDownloadManager.DownloadResult result = manager.downloadAll(
                        requiredMods,
                        new ModDownloadManager.ProgressListener() {
                            @Override public void onStart(String modId, int total, int current) {
                                setStatus(modId, RowState.DOWNLOADING, null);
                            }
                            @Override public void onComplete(String modId) {
                                setStatus(modId, RowState.DONE, null);
                            }
                            @Override public void onError(String modId, String reason) {
                                setStatus(modId, RowState.FAILED, reason);
                                LOGGER.error("Conduit: failed to download {}: {}", modId, reason);
                            }
                        }
                );

                minecraft.execute(() -> {
                    if (result.failed().isEmpty()) {
                        if (serverAddress != null) {
                            ServerInstallRecord record = ServerInstallRecord.forServer(
                                    serverAddress.getHost(), serverAddress.getPort());
                            record.save(payload.serverName(), requiredMods, result.installedFiles());
                            // Hotwire can add mods live, but not stash another server's mods.
                            hotwireEligible = new ModStashManager()
                                    .reconcile(record.key(), record.ownedJarFilenames()).movedOut() == 0;
                        }
                        hotwireJars = result.installedFiles().values().stream().distinct()
                                .map(name -> FabricLoader.getInstance().getGameDir().resolve("mods").resolve(name))
                                .toList();
                        phase = Phase.DONE;
                        finishedCount = requiredMods.size();
                        finishedAt = ConduitUi.now();
                        setFooter(hotwireReady() && !hotwireJars.isEmpty()
                                ? "All mods verified. Load them live, or restart."
                                : "All mods verified. " + reloadHint("them"), ConduitUi.GREEN, ConduitUi.Icon.CHECK);
                        ConduitSounds.success();
                        progressShower(ConduitUi.GREEN);
                    } else {
                        phase = Phase.ERROR;
                        failedCount = result.failed().size();
                        // Rows whose failure was not reported individually still must not look busy.
                        for (int i = 0; i < rowState.length; i++) {
                            if (rowState[i] == RowState.DOWNLOADING || rowState[i] == RowState.QUEUED) {
                                rowState[i] = RowState.FAILED;
                            }
                        }
                        setFooter(failedCount + (failedCount == 1 ? " mod" : " mods")
                                + " failed. Hover a row for details.", ConduitUi.RED, ConduitUi.Icon.WARN);
                        ConduitSounds.failure();
                    }
                    applyPhaseButtons();
                });
            } catch (Exception e) {
                LOGGER.error("Conduit download error: {}", e.getMessage(), e);
                minecraft.execute(() -> {
                    phase = Phase.ERROR;
                    for (int i = 0; i < rowState.length; i++) {
                        if (rowState[i] != RowState.DONE) {
                            rowState[i] = RowState.FAILED;
                            rowReason[i] = "Download stopped: " + e.getMessage();
                        }
                    }
                    failedCount = (int) Arrays.stream(rowState).filter(s -> s == RowState.FAILED).count();
                    setFooter("Error: " + e.getMessage(), ConduitUi.RED, ConduitUi.Icon.WARN);
                    ConduitSounds.failure();
                    applyPhaseButtons();
                });
            }
        });
    }

    private void finishInstallation() {
        if (hotwireApplied) { skipAndJoin(); return; }
        if (!hotwireReady() || hotwireJars.isEmpty()) { restart(); return; }
        try {
            if (!HotwireBridge.promptAccepted()) {
                // Hotwire shows its own one-time prompt; nothing happens until it is accepted.
                setFooter("Accept Hotwire's prompt, then press Load live again.", ConduitUi.YELLOW,
                        ConduitUi.Icon.INFO);
                ConduitSounds.notice();
                return;
            }
            hotwireAttempted = true;
            if (com.jacob0225.conduit.client.compat.HotwireServerSwitch.running(hotwireJars)) {
                // Hotwire already runs these exact mods; nothing to load.
                hotwireApplied = true;
                phase = Phase.DONE;
                finishedAt = ConduitUi.now();
                setFooter("Mods are already live. Press Join server.", ConduitUi.GREEN, ConduitUi.Icon.CHECK);
                Arrays.fill(rowState, RowState.READY);
                ConduitSounds.success();
                progressShower(ConduitUi.GREEN);
                applyPhaseButtons();
                return;
            }
            phase = Phase.LIVE;
            setFooter("Preparing the Hotwire plan...", ConduitUi.ACCENT, ConduitUi.Icon.INFO);
            applyPhaseButtons();
            ConduitSounds.start();
            HotwireBridge.plan(hotwireJars).whenComplete((plan, failure) -> minecraft.execute(() -> {
                if (failure != null) { hotwireFallback(failure, "Live loading failed (see log)."); return; }
                try {
                    if (!plan.canApply()) {
                        plan.cancel();
                        hotwireFallback(null, "Hotwire can't load this mod set live.");
                        return;
                    }
                    String description = plan.description();
                    List<String> lines = description.isBlank() ? List.of()
                            : Arrays.stream(description.split("\\n")).filter(s -> !s.isBlank()).toList();
                    minecraft.setScreen(new HotwirePlanScreen(this, payload.serverName(), lines,
                            accepted -> onPlanDecision(plan, accepted)));
                } catch (Exception e) { hotwireFallback(e, "Live loading failed (see log)."); }
            }));
        } catch (Exception | LinkageError e) {
            hotwireAttempted = true;
            hotwireFallback(e, "Live loading failed (see log).");
        }
    }

    private void onPlanDecision(HotwireBridge.Plan plan, boolean accepted) {
        minecraft.setScreen(this);
        if (!accepted) {
            try { plan.cancel(); } catch (Exception e) { LOGGER.warn("Hotwire cancel failed", e); }
            hotwireFallback(null, "Live loading cancelled.");
            return;
        }
        phase = Phase.LIVE;
        setFooter("Applying the Hotwire plan...", ConduitUi.ACCENT, ConduitUi.Icon.INFO);
        applyPhaseButtons();
        try {
            plan.accept().whenComplete((done, error) -> minecraft.execute(() -> {
                // Judge by what is actually running: Hotwire's result can say "not done"
                // (e.g. a cleanup queued for next start) after the mods are already live.
                if (!com.jacob0225.conduit.client.compat.HotwireServerSwitch.running(hotwireJars)) {
                    hotwireFallback(error, "Hotwire could not finish live loading.");
                    return;
                }
                if (error != null || !Boolean.TRUE.equals(done)) {
                    LOGGER.info("Hotwire reported an unfinished switch (done={}), but the new mods "
                            + "are running; treating it as live", done, error);
                }
                hotwireApplied = true;
                phase = Phase.DONE;
                finishedAt = ConduitUi.now();
                setFooter("Mods loaded live. Press Join server to reconnect.", ConduitUi.GREEN, ConduitUi.Icon.CHECK);
                Arrays.fill(rowState, RowState.READY);
                ConduitSounds.success();
                progressShower(ConduitUi.GREEN);
                applyPhaseButtons();
            }));
        } catch (Exception e) { hotwireFallback(e, "Hotwire could not start live loading."); }
    }

    private void hotwireFallback(Throwable error, String reason) {
        phase = Phase.DONE;
        if (error != null) LOGGER.warn("Hotwire live loading failed; restart required", error);
        setFooter(reason + " " + reloadHint("the mods"), ConduitUi.YELLOW, ConduitUi.Icon.WARN);
        ConduitSounds.notice();
        applyPhaseButtons();
    }

    private void restart() {
        InstanceRestarter.restart();
    }

    private void skipAndJoin() {
        if (serverAddress == null) {
            LOGGER.warn("Join requested but no server address is available.");
            backToParent();
            return;
        }
        LOGGER.info("Joining {} from the Conduit screen", serverAddress);
        ConnectScreen.startConnecting(
                parent != null ? parent : this,
                minecraft,
                serverAddress,
                serverData,
                false,
                null // No transfer state: this is an ordinary server join.
        );
    }

    private void backToParent() {
        ConduitParticles.clear();
        minecraft.setScreen(parent);
    }

    private void setStatus(String modId, RowState state, String reason) {
        for (int i = 0; i < requiredMods.size(); i++) {
            if (requiredMods.get(i).modId().equals(modId)) {
                final int idx = i;
                minecraft.execute(() -> {
                    RowState previous = rowState[idx];
                    rowState[idx] = state;
                    if (reason != null) rowReason[idx] = reason;
                    if (previous != RowState.DONE && state == RowState.DONE) {
                        ConduitSounds.step(finishedCount);
                        finishedCount++;
                        rowBurst(idx, ConduitUi.GREEN, 12);
                    }
                    if (previous != RowState.FAILED && state == RowState.FAILED) {
                        failedCount++;
                        ConduitSounds.stepFailed();
                        rowBurst(idx, ConduitUi.RED, 8);
                    }
                    if (state == RowState.DOWNLOADING) keepRowVisible(idx);
                });
                return;
            }
        }
    }

    /** Particle burst from a row's status icon, if that row is on screen. */
    private void rowBurst(int idx, int color, int count) {
        int row = idx - scrollOffset;
        if (row < 0 || row >= visibleModRows) return;
        ConduitParticles.burst(listLeft + 10, listTop + row * rowH + (rowH - 2) / 2.0F, color, count, 70.0F);
    }

    /** A celebratory shower along the progress bar. */
    private void progressShower(int color) {
        ConduitParticles.shower(panelLeft() + 12, panelRight() - 12, panelTop() + 63, color, 45);
        ConduitParticles.bubbles(panelLeft() + 26, panelTop() + 30, 6);
    }

    /** Auto-scroll so the mod being downloaded stays on screen. */
    private void keepRowVisible(int idx) {
        if (visibleModRows <= 0) return;
        if (idx < scrollOffset) scrollOffset = idx;
        else if (idx >= scrollOffset + visibleModRows) scrollOffset = idx - visibleModRows + 1;
    }
}
