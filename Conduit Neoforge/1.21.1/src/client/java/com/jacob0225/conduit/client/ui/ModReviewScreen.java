package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.CurseForgeClient;
import com.jacob0225.conduit.client.download.DependencyResolver;
import com.jacob0225.conduit.client.download.InstalledModIndex;
import com.jacob0225.conduit.client.download.ModDownloadManager;
import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.download.ModrinthClient;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.client.util.InstanceRestarter;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import com.jacob0225.conduit.client.PlatformPaths;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Install screen shown when a join is intercepted and the client is missing
 * required mods.
 */
public class ModReviewScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/UI");

    private static final int COL_WHITE = 0xFFFFFFFF;
    private static final int COL_MUTED = 0xFFB7C1CC;
    private static final int COL_DIM = 0xFF768390;
    private static final int COL_GREEN = 0xFF65D887;
    private static final int COL_YELLOW = 0xFFFFD166;
    private static final int COL_RED = 0xFFFF6B6B;
    private static final int COL_ROW = 0x331D2734;
    private static final int COL_ROW_ALT = 0x44232F3E;
    private static final int COL_BORDER = 0x55FFFFFF;

    private enum Phase { PROMPT, DOWNLOADING, DONE, ERROR }

    private final ClientConfig config = ClientConfig.get();
    private final ClientConfig.Theme theme = config.theme();
    private final ManifestPayload payload;
    private final ServerAddress serverAddress;
    private final ServerData serverData;
    private final Screen parent;
    private final List<ModEntry> requiredMods;
    private final int optionalCount;
    private final List<String> modStatus;
    private final List<Integer> modColor;

    private Phase phase = Phase.PROMPT;
    private Button primaryButton;
    private Button secondaryButton;
    private Button skipButton;
    private String footerText = "";
    private int footerColor = COL_WHITE;
    private int finishedCount = 0;
    private int failedCount = 0;
    private String activeModId = "";
    private boolean autoStarted = false;

    public ModReviewScreen(ManifestPayload payload,
                           ServerAddress address, ServerData serverData, Screen parent) {
        super(Component.literal("Conduit - Mod Sync"));
        this.payload = payload;
        this.serverAddress = address;
        this.serverData = serverData;
        this.parent = parent;
        this.requiredMods = payload.mods().stream().filter(ModEntry::required).toList();
        this.optionalCount = (int) payload.mods().stream().filter(mod -> !mod.required()).count();
        this.modStatus = new ArrayList<>();
        this.modColor = new ArrayList<>();
        for (ModEntry ignored : requiredMods) {
            modStatus.add("Queued");
            modColor.add(COL_DIM);
        }
    }

    @Override
    protected void init() {
        int panelWidth = panelWidth();
        int buttonY = panelBottom() - 26;
        int gap = 6;
        int buttonWidth = Math.min(112, (panelWidth - 44 - gap * 2) / 3);
        int startX = this.width / 2 - (buttonWidth * 3 + gap * 2) / 2;

        primaryButton = Button.builder(Component.literal(shortLabel("Download", "Get")), btn -> {
            switch (phase) {
                case PROMPT, ERROR -> startDownload();
                case DONE -> restart();
                default -> { }
            }
        }).bounds(startX, buttonY, buttonWidth, 18).build();

        skipButton = Button.builder(Component.literal(shortLabel("Skip", "Skip")), btn -> skipAndJoin())
                .bounds(startX + buttonWidth + gap, buttonY, buttonWidth, 18)
                .build();

        secondaryButton = Button.builder(Component.literal(shortLabel("Cancel", "Back")), btn -> backToParent())
                .bounds(startX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 18)
                .build();

        addRenderableWidget(primaryButton);
        addRenderableWidget(skipButton);
        addRenderableWidget(secondaryButton);
        applyPhaseButtons();

        if (phase == Phase.PROMPT && config.autoStartDownloads() && !autoStarted) {
            autoStarted = true;
            startDownload();
        }
    }

    private void applyPhaseButtons() {
        switch (phase) {
            case PROMPT -> {
                primaryButton.setMessage(Component.literal(shortLabel("Download", "Get")));
                primaryButton.active = true;
                primaryButton.visible = true;
                secondaryButton.setMessage(Component.literal(shortLabel("Cancel", "Back")));
                secondaryButton.active = true;
                secondaryButton.visible = true;
                skipButton.setMessage(Component.literal("Skip"));
                skipButton.active = true;
                skipButton.visible = true;
            }
            case DOWNLOADING -> {
                primaryButton.visible = false;
                primaryButton.active = false;
                secondaryButton.setMessage(Component.literal(shortLabel("Installing", "Working")));
                secondaryButton.active = false;
                secondaryButton.visible = true;
                skipButton.visible = false;
                skipButton.active = false;
            }
            case DONE -> {
                primaryButton.setMessage(Component.literal(shortLabel("Restart", "Restart")));
                primaryButton.visible = true;
                primaryButton.active = true;
                secondaryButton.setMessage(Component.literal("Back"));
                secondaryButton.active = true;
                secondaryButton.visible = true;
                skipButton.visible = false;
                skipButton.active = false;
            }
            case ERROR -> {
                primaryButton.setMessage(Component.literal("Retry"));
                primaryButton.visible = true;
                primaryButton.active = true;
                secondaryButton.setMessage(Component.literal("Back"));
                secondaryButton.active = true;
                secondaryButton.visible = true;
                skipButton.visible = false;
                skipButton.active = false;
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        if (config.dimBackground()) {
            g.fill(0, 0, this.width, this.height, 0xB0000000);
        } else {
            g.fill(0, 0, this.width, this.height, theme.background());
        }

        int left = panelLeft();
        int top = panelTop();
        int right = panelRight();
        int bottom = panelBottom();
        drawPanel(g, left, top, right, bottom);

        int cx = this.width / 2;
        drawHeader(g, cx, top + 12);

        if (phase == Phase.PROMPT) {
            renderPrompt(g, left, top, right);
        } else {
            renderProgress(g, left, top, right, bottom);
        }

        if (!footerText.isEmpty()) {
            drawCentered(g, footerText, cx, bottom - 40, footerColor);
        }

        super.render(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        backToParent();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return phase != Phase.DOWNLOADING;
    }

    private void renderPrompt(GuiGraphics g, int left, int top, int right) {
        int cx = this.width / 2;
        int y = top + 48;

        drawCentered(g, payload.serverName(), cx, y, COL_WHITE);
        y += 18;
        String summary = requiredMods.size() + " required mod" + (requiredMods.size() == 1 ? "" : "s");
        if (config.showOptionalMods() && optionalCount > 0) {
            summary += " + " + optionalCount + " optional";
        }
        drawCentered(g, summary, cx, y, theme.accentSoft());
        y += 20;

        drawCentered(g, "Download the missing mods for this server.", cx, y, COL_MUTED);
        y += 12;
        drawCentered(g, "Minecraft will restart afterward to load them.", cx, y, COL_MUTED);
        y += 14;
        drawCentered(g, "Skip may disconnect you.", cx, y, COL_YELLOW);

        if (requiredMods.isEmpty()) {
            footerText = "No required mods are missing.";
            footerColor = COL_GREEN;
        }
    }

    private void renderProgress(GuiGraphics g, int left, int top, int right, int bottom) {
        int cx = this.width / 2;
        int y = top + 48;
        String title = switch (phase) {
            case DOWNLOADING -> "Installing verified mods";
            case DONE -> "Ready to restart";
            case ERROR -> "Some downloads need attention";
            default -> "Download review";
        };
        drawCentered(g, title, cx, y, COL_WHITE);
        y += 16;
        drawCentered(g, payload.serverName(), cx, y, COL_MUTED);
        y += 16;

        drawProgressBar(g, left + 24, y, right - 24, progressRatio());
        y += 16;
        String summary = finishedCount + "/" + requiredMods.size() + " complete";
        if (failedCount > 0) summary += " - " + failedCount + " failed";
        drawCentered(g, summary, cx, y, failedCount > 0 ? COL_RED : COL_MUTED);
        y += 14;

        if (config.showDownloadDetails()) {
            renderModRows(g, left + 26, y, right - 26, bottom - 68);
        } else if (!activeModId.isEmpty() && phase == Phase.DOWNLOADING) {
            drawCentered(g, "Now installing: " + trimToWidth(activeModId, right - left - 72),
                    cx, y + 18, COL_DIM);
        }
    }

    private void renderModRows(GuiGraphics g, int left, int top, int right, int bottom) {
        int rowH = config.compactDownloadUi() ? 14 : 18;
        int maxRows = Math.max(1, (bottom - top) / rowH);
        int count = Math.min(requiredMods.size(), maxRows);

        for (int i = 0; i < count; i++) {
            int y = top + i * rowH;
            g.fill(left, y, right, y + rowH - 2, i % 2 == 0 ? COL_ROW : COL_ROW_ALT);
            ModEntry entry = requiredMods.get(i);
            String name = trimToWidth(entry.displayName(), right - left - 112);
            g.drawString(font, statusMark(modStatus.get(i)), left + 7, y + 4, modColor.get(i), true);
            g.drawString(font, name, left + 24, y + 4, COL_WHITE, true);
            String version = trimToWidth(entry.requiredVersion(), 82);
            g.drawString(font, version, right - font.width(version) - 8, y + 4, COL_MUTED, true);
        }

        if (requiredMods.size() > count) {
            drawCentered(g, "+" + (requiredMods.size() - count) + " more mods", this.width / 2,
                    bottom - 11, COL_DIM);
        }
    }

    private void drawHeader(GuiGraphics g, int cx, int y) {
        drawCentered(g, "CONDUIT MOD SYNC", cx, y, theme.accentSoft());
        g.fill(cx - 92, y + 12, cx + 92, y + 14, 0x552A3442);
        g.fill(cx - 92, y + 12, cx - 24, y + 14, theme.accent());
    }

    private void drawPanel(GuiGraphics g, int left, int top, int right, int bottom) {
        g.fill(left + 4, top + 5, right + 4, bottom + 5, 0x66000000);
        g.fill(left, top, right, bottom, theme.panel());
        g.fill(left, top, right, top + 2, theme.accent());
        g.fill(left, bottom - 1, right, bottom, COL_BORDER);
        g.fill(left, top, left + 1, bottom, COL_BORDER);
        g.fill(right - 1, top, right, bottom, COL_BORDER);
    }

    private void drawProgressBar(GuiGraphics g, int left, int top, int right, double ratio) {
        int height = 10;
        g.fill(left, top, right, top + height, 0x66131A24);
        g.fill(left + 1, top + 1, right - 1, top + height - 1, 0xAA0D1118);
        int fillRight = left + 1 + (int) ((right - left - 2) * clamp(ratio, 0.0, 1.0));
        g.fill(left + 1, top + 1, fillRight, top + height - 1,
                failedCount > 0 ? COL_RED : theme.accent());
    }

    private void drawCentered(GuiGraphics g, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, true);
    }

    private String trimToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String suffix = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }

    private static String statusMark(String status) {
        return switch (status) {
            case "Done" -> "OK";
            case "Downloading" -> ">>";
            case "Failed" -> "!!";
            default -> "--";
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double progressRatio() {
        if (requiredMods.isEmpty()) return 1.0;
        return (double) finishedCount / (double) requiredMods.size();
    }

    private int panelWidth() {
        return Math.min(this.width - 20, config.compactDownloadUi() ? 360 : 420);
    }

    private int panelHeight() {
        int desired = config.compactDownloadUi() ? 190 : 210;
        return Math.min(this.height - 20, desired);
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

    private String shortLabel(String normal, String narrow) {
        return panelWidth() < 340 ? narrow : normal;
    }

    private void startDownload() {
        if (requiredMods.isEmpty()) {
            phase = Phase.DONE;
            footerText = "No required downloads. Restart only if your mod folder changed.";
            footerColor = COL_GREEN;
            applyPhaseButtons();
            return;
        }

        phase = Phase.DOWNLOADING;
        applyPhaseButtons();
        footerText = "";
        finishedCount = 0;
        failedCount = 0;
        activeModId = "";
        for (int i = 0; i < modStatus.size(); i++) {
            modStatus.set(i, "Queued");
            modColor.set(i, COL_DIM);
        }

        String mcVersion = PlatformPaths.minecraftVersion();

        ModrinthClient modrinth = new ModrinthClient(mcVersion);
        CurseForgeClient curseForge = config.hasCurseForgeApiKey()
                ? new CurseForgeClient(config.curseForgeApiKey(), mcVersion)
                : null;
        InstalledModIndex index = new InstalledModIndex();
        DependencyResolver deps = new DependencyResolver(index);
        ModDownloadManager manager = new ModDownloadManager(modrinth, curseForge, deps);

        CompletableFuture.runAsync(() -> {
            try {
                manager.prepare();

                ModDownloadManager.DownloadResult result = manager.downloadAll(
                        requiredMods,
                        new ModDownloadManager.ProgressListener() {
                            @Override public void onStart(String modId, int total, int current) {
                                setStatus(modId, "Downloading", COL_YELLOW);
                            }
                            @Override public void onComplete(String modId) {
                                setStatus(modId, "Done", COL_GREEN);
                            }
                            @Override public void onError(String modId, String reason) {
                                setStatus(modId, "Failed", COL_RED);
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

                            ModStashManager stash = new ModStashManager();
                            stash.reconcile(record.key(), record.ownedJarFilenames());
                        }
                        phase = Phase.DONE;
                        finishedCount = requiredMods.size();
                        footerText = "All mods installed. Restart Minecraft, then reconnect.";
                        footerColor = COL_GREEN;
                    } else {
                        phase = Phase.ERROR;
                        failedCount = result.failed().size();
                        footerText = result.failed().size() + " mod(s) failed. Check logs, then retry.";
                        footerColor = COL_RED;
                    }
                    activeModId = "";
                    applyPhaseButtons();
                });
            } catch (Exception e) {
                LOGGER.error("Conduit download error: {}", e.getMessage(), e);
                minecraft.execute(() -> {
                    phase = Phase.ERROR;
                    footerText = "Error: " + e.getMessage();
                    footerColor = COL_RED;
                    activeModId = "";
                    applyPhaseButtons();
                });
            }
        });
    }

    private void restart() {
        InstanceRestarter.restart();
    }

    private void skipAndJoin() {
        if (serverAddress == null) {
            LOGGER.warn("Skip requested but no server address is available.");
            backToParent();
            return;
        }
        LOGGER.info("Skipping mod download - joining {} directly", serverAddress);
        ConduitJoinInterceptor.bypassNextCheck();
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                parent != null ? parent : this,
                minecraft,
                serverAddress,
                serverData,
                false,
                new net.minecraft.client.multiplayer.TransferState(java.util.Map.of())
        );
    }

    private void backToParent() {
        minecraft.setScreen(parent);
    }

    private void setStatus(String modId, String status, int color) {
        for (int i = 0; i < requiredMods.size(); i++) {
            if (requiredMods.get(i).modId().equals(modId)) {
                final int idx = i;
                minecraft.execute(() -> {
                    String previous = modStatus.get(idx);
                    modStatus.set(idx, status);
                    modColor.set(idx, color);
                    if ("Downloading".equals(status)) {
                        activeModId = requiredMods.get(idx).displayName();
                    }
                    if (!"Done".equals(previous) && "Done".equals(status)) {
                        finishedCount++;
                    }
                    if (!"Failed".equals(previous) && "Failed".equals(status)) {
                        failedCount++;
                    }
                });
                return;
            }
        }
    }
}
