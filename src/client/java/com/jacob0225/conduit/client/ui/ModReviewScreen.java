package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.ConduitLock;
import com.jacob0225.conduit.client.download.*;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Overlay screen shown while Conduit installs mods.
 *
 * The player stays connected and frozen in place (via ConduitLock + mixins).
 * The game world renders behind this screen.
 *
 * Phase 1 — PROMPT:  "Server requires X mods. Download them?"
 * Phase 2 — DOWNLOADING: per-mod progress list
 * Phase 3 — DONE: unlock + close → player is already in the server
 *
 * Cancel at any point unlocks the player and closes the screen, leaving them
 * connected but without the required mods (the server may kick them).
 */
public class ModReviewScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/UI");

    private static final int COL_WHITE   = 0xFFFFFFFF;
    private static final int COL_GREY    = 0xFFAAAAAA;
    private static final int COL_YELLOW  = 0xFFFFCC00;
    private static final int COL_GREEN   = 0xFF55FF55;
    private static final int COL_RED     = 0xFFFF5555;
    private static final int COL_PENDING = 0xFFAAAAAA;

    // Semi-transparent dark background for the overlay panel
    private static final int PANEL_COLOR = 0xCC000000;

    private enum Phase { PROMPT, DOWNLOADING, DONE, ERROR }
    private Phase phase = Phase.PROMPT;

    private final ManifestPayload payload;
    private final ManifestVerifier.DiffResult diff;

    private final List<ModEntry> modList;
    private final List<String>   modStatus;
    private final List<Integer>  modColor;

    private Button yesButton;
    private Button cancelButton;
    private String footerText  = "";
    private int    footerColor = COL_WHITE;

    public ModReviewScreen(ManifestPayload payload, ManifestVerifier.DiffResult diff) {
        super(Component.literal("Conduit — Mod Sync"));
        this.payload = payload;
        this.diff    = diff;

        modList   = diff.allNeeded();
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

        yesButton = Button.builder(
                Component.literal("Yes, Download"),
                btn -> startDownload()
        ).bounds(cx - 106, by, 100, 20).build();

        cancelButton = Button.builder(
                Component.literal("Cancel"),
                btn -> onClose()
        ).bounds(cx + 6, by, 100, 20).build();

        addRenderableWidget(yesButton);
        addRenderableWidget(cancelButton);
    }

    /**
     * Render a dark semi-transparent panel in the centre so text is readable
     * over the game world, then draw the UI on top.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // Draw dark overlay panel (not full-screen, just the centre region)
        int panelW = Math.min(this.width - 40, 420);
        int panelH = Math.min(this.height - 60, 260);
        int px = (this.width  - panelW) / 2;
        int py = (this.height - panelH) / 2 - 10;
        g.fill(px, py, px + panelW, py + panelH, PANEL_COLOR);

        // Let the super call render buttons on top of the panel
        super.extractRenderState(g, mx, my, delta);

        int cx = this.width / 2;

        if (phase == Phase.PROMPT) {
            renderPrompt(g, cx);
        } else {
            renderProgress(g, cx);
        }

        if (!footerText.isEmpty()) {
            drawCentered(g, footerText, cx, this.height - 56, footerColor);
        }
    }

    /** Never close on ESC — player must make an explicit choice. */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /**
     * Called when the screen closes for any reason (Cancel button or unlock-and-close
     * after success). Always release the lock so the player isn't frozen forever.
     */
    @Override
    public void onClose() {
        ConduitLock.unlock();
        super.onClose();
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

        drawCentered(g, "You are paused. Download mods to continue?", cx, y, COL_YELLOW);
    }

    private void renderProgress(GuiGraphicsExtractor g, int cx) {
        drawCentered(g, "§lInstalling mods for " + payload.serverName(), cx, this.height / 2 - 80, COL_WHITE);

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

    // ── Download logic ────────────────────────────────────────────────────────

    private void startDownload() {
        phase = Phase.DOWNLOADING;
        yesButton.active   = false;
        cancelButton.active = false;

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
                        // Success — unlock player and close screen.
                        // The connection is still live; player is already in the server.
                        // Mods are on disk; they'll load properly on next game restart.
                        footerText  = "§aInstalled! Closing in a moment…";
                        footerColor = COL_GREEN;
                        // Brief pause so the player can see all the green ticks
                        CompletableFuture.runAsync(() -> {
                            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                            minecraft.execute(this::onClose);
                        });
                    } else {
                        phase = Phase.ERROR;
                        footerText  = "§cSome mods failed. Check logs.";
                        footerColor = COL_RED;
                        cancelButton.setMessage(Component.literal("Close"));
                        cancelButton.active = true;
                    }
                });

            } catch (Exception e) {
                LOGGER.error("Conduit download error: {}", e.getMessage(), e);
                minecraft.execute(() -> {
                    phase       = Phase.ERROR;
                    footerText  = "§cError: " + e.getMessage();
                    footerColor = COL_RED;
                    cancelButton.setMessage(Component.literal("Close"));
                    cancelButton.active = true;
                });
            }
        });
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
