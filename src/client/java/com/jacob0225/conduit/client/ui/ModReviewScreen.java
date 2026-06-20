package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.download.*;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Two-phase screen:
 *
 * Phase 1 — PROMPT
 *   Simple yes/no: "This server has X mods. Download them automatically?"
 *   [Yes]  → switches to DOWNLOADING phase
 *   [No]   → closes screen
 *
 * Phase 2 — DOWNLOADING
 *   Shows a live list of every mod with a per-mod status line:
 *     ⏳ Downloading...   ✔ Done   ✘ Failed
 *   When all done: shows "Restart required" + a [Close] button.
 */
public class ModReviewScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/UI");

    // Colours
    private static final int COL_WHITE   = 0xFFFFFF;
    private static final int COL_GREY    = 0xAAAAAA;
    private static final int COL_YELLOW  = 0xFFCC00;
    private static final int COL_GREEN   = 0x55FF55;
    private static final int COL_RED     = 0xFF5555;
    private static final int COL_PENDING = 0xAAAAAA;

    private enum Phase { PROMPT, DOWNLOADING, DONE, ERROR }
    private Phase phase = Phase.PROMPT;

    private final ManifestPayload payload;
    private final ManifestVerifier.DiffResult diff;

    // Per-mod download status (parallel to diff.allNeeded())
    private final List<ModEntry>  modList;
    private final List<String>    modStatus;   // one status string per mod
    private final List<Integer>   modColor;    // matching colour

    private Button yesButton;
    private Button noButton;
    private String footerText = "";
    private int    footerColor = COL_WHITE;

    public ModReviewScreen(ManifestPayload payload, ManifestVerifier.DiffResult diff) {
        super(Component.literal("Conduit — Mod Sync"));
        this.payload = payload;
        this.diff    = diff;

        modList   = diff.allNeeded();
        modStatus = new ArrayList<>();
        modColor  = new ArrayList<>();
        for (ModEntry e : modList) {
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

        noButton = Button.builder(
                Component.literal("No Thanks"),
                btn -> onClose()
        ).bounds(cx + 6, by, 100, 20).build();

        addRenderableWidget(yesButton);
        addRenderableWidget(noButton);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        int cx = this.width / 2;

        if (phase == Phase.PROMPT) {
            renderPrompt(g, cx);
        } else {
            renderProgress(g, cx);
        }

        // Footer line (errors / restart message)
        if (!footerText.isEmpty()) {
            g.drawCenteredString(font, footerText, cx, this.height - 56, footerColor);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Only allow ESC on the initial prompt
        return phase == Phase.PROMPT;
    }

    // ── Render helpers ────────────────────────────────────────────────────────

    private void renderPrompt(GuiGraphics g, int cx) {
        int y = this.height / 2 - 40;

        g.drawCenteredString(font,
                "§l" + payload.serverName() + " uses Conduit",
                cx, y, COL_WHITE);
        y += 18;

        int required = (int) modList.stream().filter(ModEntry::required).count();
        int optional = modList.size() - required;

        String line2 = required + " required mod" + (required != 1 ? "s" : "");
        if (optional > 0) line2 += ", " + optional + " optional";

        g.drawCenteredString(font, line2, cx, y, COL_GREY);
        y += 14;

        g.drawCenteredString(font,
                "Would you like to download them automatically?",
                cx, y, COL_YELLOW);
    }

    private void renderProgress(GuiGraphics g, int cx) {
        // Title bar
        g.drawCenteredString(font, "§lDownloading mods for " + payload.serverName(),
                cx, 16, COL_WHITE);

        // Scrollable-ish mod list (clip to available space above footer)
        int listTop    = 36;
        int listBottom = this.height - 64;
        int lineH      = 13;
        int x          = cx - 140;
        int y          = listTop;

        for (int i = 0; i < modList.size(); i++) {
            if (y + lineH > listBottom) break; // simple clip; full scroll is a future TODO

            ModEntry entry = modList.get(i);
            String   icon  = iconFor(modStatus.get(i));
            int      col   = modColor.get(i);

            String line = icon + " §f" + entry.displayName()
                    + " §7" + entry.requiredVersion()
                    + (entry.required() ? "" : " §8(optional)");

            g.drawString(font, line, x, y, col);
            y += lineH;
        }
    }

    private static String iconFor(String status) {
        return switch (status) {
            case "Done"        -> "§a✔";
            case "Downloading" -> "§e⏳";
            case "Failed"      -> "§c✘";
            default            -> "§7·"; // Pending
        };
    }

    // ── Download logic ────────────────────────────────────────────────────────

    private void startDownload() {
        phase = Phase.DOWNLOADING;
        yesButton.active = false;
        noButton.active  = false;

        String mcVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("26.1");

        ModrinthClient     modrinth  = new ModrinthClient(mcVersion);
        InstalledModIndex  index     = new InstalledModIndex();
        DependencyResolver deps      = new DependencyResolver(index);
        // CurseForge: pass null until a client config with API key is wired up
        ModDownloadManager manager   = new ModDownloadManager(modrinth, null, deps);

        CompletableFuture.runAsync(() -> {
            try {
                manager.prepare();

                ModDownloadManager.DownloadResult result = manager.downloadAll(
                        modList,
                        new ModDownloadManager.ProgressListener() {

                            @Override
                            public void onStart(String modId, int total, int current) {
                                setStatus(modId, "Downloading", COL_YELLOW);
                            }

                            @Override
                            public void onComplete(String modId) {
                                setStatus(modId, "Done", COL_GREEN);
                            }

                            @Override
                            public void onError(String modId, String reason) {
                                setStatus(modId, "Failed", COL_RED);
                                LOGGER.error("Conduit: failed to download {}: {}", modId, reason);
                            }
                        }
                );

                minecraft.execute(() -> {
                    phase = Phase.DONE;
                    if (result.failed().isEmpty()) {
                        footerText  = "§aAll done! Please restart the game to load the new mods.";
                        footerColor = COL_GREEN;
                    } else {
                        footerText  = "§cSome mods failed. Check logs for details.";
                        footerColor = COL_RED;
                        phase = Phase.ERROR;
                    }
                    noButton.setMessage(Component.literal("Close"));
                    noButton.active = true;
                });

            } catch (Exception e) {
                LOGGER.error("Conduit download error: {}", e.getMessage(), e);
                minecraft.execute(() -> {
                    phase       = Phase.ERROR;
                    footerText  = "§cUnexpected error: " + e.getMessage();
                    footerColor = COL_RED;
                    noButton.setMessage(Component.literal("Close"));
                    noButton.active = true;
                });
            }
        });
    }

    /** Thread-safe status update — can be called from the download thread. */
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
