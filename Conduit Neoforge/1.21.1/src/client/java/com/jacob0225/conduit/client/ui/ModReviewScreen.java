package com.jacob0225.conduit.client.ui;

import com.mojang.math.Axis;
import com.jacob0225.conduit.client.config.ClientConfig;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
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
    private static final ResourceLocation CONDUIT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("conduit", "textures/gui/conduit.png");

    private static final int COL_WHITE = 0xFFFFFFFF;
    private static final int COL_MUTED = 0xFFA0A0A0;
    private static final int COL_DIM = 0xFF707070;
    private static final int COL_GREEN = 0xFF55FF55;
    private static final int COL_YELLOW = 0xFFFFFF55;
    private static final int COL_RED = 0xFFFF5555;
    private static final int COL_GOLD = 0xFFFFAA00;
    private static final int COL_OCEAN = 0xFF35B9E8;
    private static final int COL_ROW = 0xAA202020;
    private static final int COL_ROW_ALT = 0xAA292929;

    private enum Phase { PROMPT, DOWNLOADING, DONE, ERROR }

    private final ClientConfig config = ClientConfig.get();
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
    private int scrollOffset = 0;
    private int listTop = 0;
    private int listBottom = 0;
    private int visibleModRows = 0;

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
        if (parent != null) parent.render(g, mx, my, delta);

        int left = panelLeft();
        int top = panelTop();
        int right = panelRight();
        int bottom = panelBottom();

        if (phase == Phase.PROMPT) {
            renderPrompt(g, left, top, right);
        } else {
            renderProgress(g, left, top, right, bottom);
        }

        if (!footerText.isEmpty()) {
            drawCentered(g, footerText, this.width / 2, bottom - 40, footerColor);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        if (phase != Phase.PROMPT && mouseY >= listTop && mouseY < listBottom
                && requiredMods.size() > visibleModRows && verticalAmount != 0.0D) {
            int maxOffset = requiredMods.size() - visibleModRows;
            scrollOffset = Math.max(0, Math.min(maxOffset,
                    scrollOffset + (verticalAmount > 0.0D ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void renderPrompt(GuiGraphics g, int left, int top, int right) {
        int cx = this.width / 2;
        renderConduit(g, cx, top + 28, false);
        int y = top + 58;

        drawCentered(g, payload.serverName(), cx, y, COL_WHITE);
        y += 18;
        String summary = requiredMods.size() + " required mod" + (requiredMods.size() == 1 ? "" : "s");
        if (config.showOptionalMods() && optionalCount > 0) {
            summary += " + " + optionalCount + " optional";
        }
        drawCentered(g, summary, cx, y, COL_GOLD);
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
        renderConduit(g, cx, top + 26, phase == Phase.DOWNLOADING);
        int y = top + 52;
        String title = switch (phase) {
            case DOWNLOADING -> "Installing verified mods";
            case DONE -> "Ready to restart";
            case ERROR -> "Some downloads need attention";
            default -> "Download review";
        };
        drawCentered(g, title, cx, y, COL_WHITE);
        y += 16;
        drawCentered(g, payload.serverName(), cx, y, COL_MUTED);
        y += 14;
        drawProgressBar(g, left + 24, y, right - 24, progressRatio());
        y += 16;
        String summary = finishedCount + "/" + requiredMods.size() + " complete";
        if (failedCount > 0) summary += " - " + failedCount + " failed";
        drawCentered(g, summary, cx, y, failedCount > 0 ? COL_RED : COL_MUTED);
        y += 14;

        renderModRows(g, left + 26, y, right - 26, bottom - 68);
    }

    private void renderModRows(GuiGraphics g, int left, int top, int right, int bottom) {
        int rowH = config.compactDownloadUi() ? 14 : 18;
        int maxRows = Math.max(1, (bottom - top) / rowH);
        visibleModRows = maxRows;
        listTop = top;
        listBottom = bottom;
        int maxOffset = Math.max(0, requiredMods.size() - maxRows);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        int count = Math.min(requiredMods.size() - scrollOffset, maxRows);

        for (int row = 0; row < count; row++) {
            int i = scrollOffset + row;
            int y = top + row * rowH;
            g.fill(left, y, right, y + rowH - 2, row % 2 == 0 ? COL_ROW : COL_ROW_ALT);
            ModEntry entry = requiredMods.get(i);
            String name = trimToWidth(entry.displayName(), right - left - 112);
            g.drawString(font, statusMark(modStatus.get(i)), left + 7, y + 4, modColor.get(i), true);
            g.drawString(font, name, left + 24, y + 4, COL_WHITE, true);
            String version = trimToWidth(entry.requiredVersion(), 82);
            g.drawString(font, version, right - font.width(version) - 8, y + 4, COL_MUTED, true);
        }

        if (maxOffset > 0) {
            int trackX = right - 2;
            int trackHeight = Math.max(1, bottom - top - 2);
            int thumbHeight = Math.max(8, trackHeight * maxRows / requiredMods.size());
            int thumbY = top + 1 + (trackHeight - thumbHeight) * scrollOffset / maxOffset;
            g.fill(trackX, top, trackX + 2, bottom, 0x66000000);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, COL_OCEAN);
        }
    }

    private void drawProgressBar(GuiGraphics g, int left, int top, int right, double ratio) {
        int height = 10;
        g.fill(left, top, right, top + height, 0x66131A24);
        g.fill(left + 1, top + 1, right - 1, top + height - 1, 0xAA0D1118);
        int fillRight = left + 1 + (int) ((right - left - 2) * clamp(ratio, 0.0, 1.0));
        g.fill(left + 1, top + 1, fillRight, top + height - 1,
                failedCount > 0 ? COL_RED : COL_OCEAN);
    }

    private void renderConduit(GuiGraphics g, int centerX, int centerY, boolean spinning) {
        float angle = spinning ? (Util.getMillis() % 4000L) * 360.0F / 4000.0F : 0.0F;
        g.pose().pushPose();
        g.pose().translate(centerX, centerY, 100.0F);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angle));
        g.blit(CONDUIT_TEXTURE, -20, -22, 40, 44,
                0.0F, 0.0F, 100, 110, 100, 110);
        g.pose().popPose();
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
        int desired = config.compactDownloadUi() ? 214 : 234;
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
