package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.download.*;
import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.client.util.InstanceRestarter;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * required mods. Runs the existing download pipeline, then offers to reconnect.
 *
 * <p>Unlike the prior config-phase version, the player has NOT joined yet —
 * this screen appears instead of the connect. On success, "Restart" relaunches
 * the instance so the freshly downloaded mods load; the player then re-joins.
 * "Skip" (prompt only) joins immediately without installing anything.
 *
 * <p>Phases:
 *   PROMPT     — "Server requires N mods. Download?" + Skip
 *   DOWNLOADING — per-mod progress list, buttons disabled
 *   DONE        — "Installed!" + Restart / Back
 *   ERROR       — "Some mods failed" + Retry / Back
 */
public class ModReviewScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/UI");

    private static final int COL_WHITE   = 0xFFFFFFFF;
    private static final int COL_GREY    = 0xFFAAAAAA;
    private static final int COL_YELLOW  = 0xFFFFCC00;
    private static final int COL_GREEN   = 0xFF55FF55;
    private static final int COL_RED     = 0xFFFF5555;
    private static final int COL_PENDING = 0xFFAAAAAA;

    private static final int PANEL_COLOR = 0xCC000000;

    private enum Phase { PROMPT, DOWNLOADING, DONE, ERROR }
    private Phase phase = Phase.PROMPT;

    private final ManifestPayload payload;

    /** Captured at intercept time so Restart/Skip can re-issue the exact join. */
    private final ServerAddress serverAddress;
    private final ServerData    serverData;
    private final Screen        parent;

    private final List<ModEntry> modList;
    private final List<String>   modStatus;
    private final List<Integer>  modColor;

    private Button primaryButton;   // Yes / Restart / Retry
    private Button secondaryButton; // Cancel / Back
    private Button skipButton;      // Skip — join without downloading (PROMPT only)

    private String footerText  = "";
    private int    footerColor = COL_WHITE;

    /**
     * @param payload   the server manifest — required mods are downloaded
     * @param address   the address being joined (used to write the install record
     *                  and to re-issue the join after Skip)
     * @param serverData server data passed back to the vanilla connect
     * @param parent    screen to return to on cancel
     */
    public ModReviewScreen(ManifestPayload payload,
                           ServerAddress address, ServerData serverData, Screen parent) {
        super(Component.literal("Conduit — Mod Sync"));
        this.payload       = payload;
        this.serverAddress = address;
        this.serverData    = serverData;
        this.parent        = parent;

        // Download only what the manifest marks required; optional mods are
        // surfaced for information but not force-installed.
        modList   = payload.mods().stream().filter(ModEntry::required).toList();
        modStatus = new ArrayList<>();
        modColor  = new ArrayList<>();
        for (ModEntry ignored : modList) {
            modStatus.add("Pending");
            modColor.add(COL_PENDING);
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height - 36;

        // Primary button action is phase-dependent: download on PROMPT, restart
        // on DONE, retry on ERROR. Dispatching inside the lambda means we only
        // change the label to change behavior — no re-binding needed.
        primaryButton = Button.builder(
                Component.literal("Yes, Download"),
                btn -> {
                    switch (phase) {
                        case PROMPT, ERROR -> startDownload();
                        case DONE          -> restart();
                        default            -> { /* disabled during DOWNLOADING */ }
                    }
                }
        ).bounds(cx - 154, by, 100, 20).build();

        secondaryButton = Button.builder(
                Component.literal("Cancel"),
                btn -> backToParent()
        ).bounds(cx + 54, by, 100, 20).build();

        // Skip joins immediately without installing anything (PROMPT only).
        skipButton = Button.builder(
                Component.literal("Skip"),
                btn -> skipAndJoin()
        ).bounds(cx - 50, by, 100, 20).build();

        addRenderableWidget(primaryButton);
        addRenderableWidget(skipButton);
        addRenderableWidget(secondaryButton);

        applyPhaseButtons();
    }

    private void applyPhaseButtons() {
        switch (phase) {
            case PROMPT -> {
                primaryButton.setMessage(Component.literal("Yes, Download"));
                primaryButton.active = true;
                primaryButton.visible = true;
                secondaryButton.setMessage(Component.literal("Cancel"));
                secondaryButton.active = true;
                secondaryButton.visible = true;
                skipButton.setMessage(Component.literal("Skip"));
                skipButton.active = true;
                skipButton.visible = true;
            }
            case DOWNLOADING -> {
                primaryButton.visible = false;
                primaryButton.active = false;
                secondaryButton.setMessage(Component.literal("Cancel"));
                secondaryButton.active = false; // atomic install — no mid-download cancel
                secondaryButton.visible = true;
                skipButton.visible = false;
                skipButton.active = false;
            }
            case DONE -> {
                primaryButton.setMessage(Component.literal("Restart"));
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
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, this.width, this.height, PANEL_COLOR);

        int cx = this.width / 2;

        if (phase == Phase.PROMPT) renderPrompt(g, cx);
        else                       renderProgress(g, cx);

        if (!footerText.isEmpty()) {
            drawCentered(g, footerText, cx, this.height - 56, footerColor);
        }

        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        backToParent();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return phase != Phase.DOWNLOADING;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void renderPrompt(GuiGraphicsExtractor g, int cx) {
        int y = this.height / 2 - 60;

        drawCentered(g, "§l" + payload.serverName() + " requires mods", cx, y, COL_WHITE);
        y += 18;

        int required = (int) modList.stream().filter(ModEntry::required).count();
        int optional = modList.size() - required;
        String line2 = required + " required mod" + (required != 1 ? "s" : "");
        if (optional > 0) line2 += ", " + optional + " optional";
        drawCentered(g, line2, cx, y, COL_GREY);
        y += 14;

        drawCentered(g, "Conduit will download them, then restart to load them.", cx, y, COL_YELLOW);
        y += 12;
        drawCentered(g, "Or click Skip to join anyway (may get disconnected).", cx, y, COL_YELLOW);
    }

    private void renderProgress(GuiGraphicsExtractor g, int cx) {
        drawCentered(g, "§lInstalling mods for " + payload.serverName(),
                cx, this.height / 2 - 80, COL_WHITE);

        int listTop    = this.height / 2 - 60;
        int listBottom = this.height - 64;
        int lineH      = 13;
        int x          = cx - 140;
        int y          = listTop;

        for (int i = 0; i < modList.size(); i++) {
            if (y + lineH > listBottom) break;
            ModEntry entry = modList.get(i);
            String line = iconFor(modStatus.get(i)) + " §f" + entry.displayName()
                    + " §7" + entry.requiredVersion()
                    + (entry.required() ? "" : " §8(optional)");
            g.text(font, line, x, y, modColor.get(i), true);
            y += lineH;
        }
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int cx, int y, int color) {
        g.text(font, text, cx - font.width(text) / 2, y, color, true);
    }

    private static String iconFor(String status) {
        return switch (status) {
            case "Done"        -> "§a✔";
            case "Downloading" -> "§e⏳";
            case "Failed"      -> "§c✘";
            default            -> "§7·";
        };
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void startDownload() {
        phase = Phase.DOWNLOADING;
        applyPhaseButtons();
        footerText = "";
        for (int i = 0; i < modStatus.size(); i++) {
            modStatus.set(i, "Pending");
            modColor.set(i, COL_PENDING);
        }

        String mcVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("26.1");

        ModrinthClient     modrinth = new ModrinthClient(mcVersion);
        InstalledModIndex  index    = new InstalledModIndex();
        DependencyResolver deps     = new DependencyResolver(index);
        ModDownloadManager manager  = new ModDownloadManager(modrinth, null, deps);

        CompletableFuture.runAsync(() -> {
            try {
                manager.prepare();

                ModDownloadManager.DownloadResult result = manager.downloadAll(
                        modList,
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
                        // Persist the install record (with jar filenames so the
                        // stash manager knows what Conduit owns for this server),
                        // then stash any jars owned by OTHER servers out of mods/
                        // so they don't conflict with this server's set on restart.
                        if (serverAddress != null) {
                            ServerInstallRecord record = ServerInstallRecord.forServer(
                                    serverAddress.getHost(), serverAddress.getPort());
                            record.save(payload.serverName(), modList, result.installedFiles());

                            ModStashManager stash = new ModStashManager();
                            stash.reconcile(record.key(), record.ownedJarFilenames());
                        }
                        phase = Phase.DONE;
                        footerText  = "§aAll mods installed. Click Restart to load them and rejoin.";
                        footerColor = COL_GREEN;
                    } else {
                        phase = Phase.ERROR;
                        footerText  = "§c" + result.failed().size() + " mod(s) failed. Check logs.";
                        footerColor = COL_RED;
                    }
                    applyPhaseButtons();
                });

            } catch (Exception e) {
                LOGGER.error("Conduit download error: {}", e.getMessage(), e);
                minecraft.execute(() -> {
                    phase = Phase.ERROR;
                    footerText  = "§cError: " + e.getMessage();
                    footerColor = COL_RED;
                    applyPhaseButtons();
                });
            }
        });
    }

    /**
     * Relaunch the instance so the freshly downloaded mods are picked up by
     * Fabric's loader. Newly placed jars aren't recognised until a full restart,
     * so re-issuing the join in-process would just hit the same registry
     * mismatch the install was meant to fix — restart is required.
     */
    private void restart() {
        InstanceRestarter.restart();
    }

    /**
     * Join immediately without downloading anything. The bypass flag is set so
     * the interceptor lets the call straight through without re-prompting.
     * The player will likely get a registry-mismatch disconnect if mods are
     * actually required, but that's their explicit choice.
     */
    private void skipAndJoin() {
        if (serverAddress == null) {
            LOGGER.warn("Skip requested but no server address is available.");
            backToParent();
            return;
        }
        LOGGER.info("Skipping mod download — joining {} directly", serverAddress);
        ConduitJoinInterceptor.bypassNextCheck();
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                parent != null ? parent : this,
                minecraft,
                serverAddress,
                serverData,
                false,
                new net.minecraft.client.multiplayer.TransferState(
                        java.util.Map.of(), java.util.Map.of(), false)
        );
    }

    private void backToParent() {
        minecraft.setScreen(parent);
    }

    private void setStatus(String modId, String status, int color) {
        for (int i = 0; i < modList.size(); i++) {
            if (modList.get(i).modId().equals(modId)) {
                final int idx = i;
                minecraft.execute(() -> {
                    modStatus.set(idx, status);
                    modColor.set(idx, color);
                });
                return;
            }
        }
    }
}
