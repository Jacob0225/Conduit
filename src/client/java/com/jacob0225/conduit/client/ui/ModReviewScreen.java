package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.network.ClientManifestHandler;
import com.jacob0225.conduit.client.download.*;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Standalone install screen shown over the title menu after the client
 * disconnects from a server whose manifest it didn't satisfy.
 *
 * <p>This is NOT an in-game overlay — the player has already been disconnected
 * (mods cannot be hot-loaded onto a live connection). The screen runs the
 * existing download pipeline, then offers to reconnect to the server whose
 * address was captured before disconnect.
 *
 * <p>Phase 1 — PROMPT:     "Server requires X mods. Download them?"
 * Phase 2 — DOWNLOADING:   per-mod progress list, buttons disabled
 * Phase 3 — DONE:          "Installed!" + Reconnect / Back to menu buttons
 * Phase 4 — ERROR:         "Some mods failed" + Retry / Back to menu buttons
 *
 * <p>Reconnect uses {@link ConnectScreen#startConnecting} with the captured
 * {@link ServerAddress}. If no address was captured (e.g. server transfer),
 * Reconnect is hidden and only "Back to menu" is offered.
 */
public class ModReviewScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/UI");

    private static final int COL_WHITE   = 0xFFFFFFFF;
    private static final int COL_GREY    = 0xFFAAAAAA;
    private static final int COL_YELLOW  = 0xFFFFCC00;
    private static final int COL_GREEN   = 0xFF55FF55;
    private static final int COL_RED     = 0xFFFF5555;
    private static final int COL_PENDING = 0xFFAAAAAA;

    // Solid dark background — no game world behind this screen anymore.
    private static final int PANEL_COLOR = 0xCC000000;

    private enum Phase { PROMPT, DOWNLOADING, DONE, ERROR }
    private Phase phase = Phase.PROMPT;

    private final ManifestPayload payload;
    private final ManifestVerifier.DiffResult diff;

    private final List<ModEntry> modList;
    private final List<String>   modStatus;
    private final List<Integer>  modColor;

    private Button primaryButton;   // Yes / (hidden during download) / Reconnect / Retry
    private Button secondaryButton; // Cancel / Back

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

        // The primary button's action depends on the current phase: download on
        // PROMPT, reconnect on DONE, retry on ERROR. The lambda dispatches rather
        // than being re-bound, so changing the label is enough to change behavior.
        primaryButton = Button.builder(
                Component.literal("Yes, Download"),
                btn -> {
                    switch (phase) {
                        case PROMPT, ERROR -> startDownload();
                        case DONE          -> reconnect();
                        default            -> { /* disabled during DOWNLOADING */ }
                    }
                }
        ).bounds(cx - 106, by, 100, 20).build();

        secondaryButton = Button.builder(
                Component.literal("Cancel"),
                btn -> backToMenu()
        ).bounds(cx + 6, by, 100, 20).build();

        addRenderableWidget(primaryButton);
        addRenderableWidget(secondaryButton);

        applyPhaseButtons();
    }

    /** Toggle button labels/visibility based on the current phase. */
    private void applyPhaseButtons() {
        switch (phase) {
            case PROMPT -> {
                primaryButton.setMessage(Component.literal("Yes, Download"));
                primaryButton.active = true;
                primaryButton.visible = true;
                secondaryButton.setMessage(Component.literal("Cancel"));
                secondaryButton.active = true;
            }
            case DOWNLOADING -> {
                primaryButton.visible = false;
                primaryButton.active = false;
                secondaryButton.setMessage(Component.literal("Cancel"));
                secondaryButton.active = false; // no mid-download cancel (atomic install)
            }
            case DONE -> {
                // Reconnect is only available if we captured an address.
                Optional<ServerAddress> addr = ClientManifestHandler.getPendingServerAddress();
                if (addr.isPresent()) {
                    primaryButton.setMessage(Component.literal("Reconnect"));
                    primaryButton.visible = true;
                    primaryButton.active = true;
                } else {
                    primaryButton.visible = false;
                    primaryButton.active = false;
                }
                secondaryButton.setMessage(Component.literal("Back to Menu"));
                secondaryButton.active = true;
            }
            case ERROR -> {
                primaryButton.setMessage(Component.literal("Retry"));
                primaryButton.visible = true;
                primaryButton.active = true;
                secondaryButton.setMessage(Component.literal("Back to Menu"));
                secondaryButton.active = true;
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // Full-screen dark background since there's no game world behind us.
        g.fill(0, 0, this.width, this.height, PANEL_COLOR);

        int cx = this.width / 2;

        if (phase == Phase.PROMPT) {
            renderPrompt(g, cx);
        } else {
            renderProgress(g, cx);
        }

        if (!footerText.isEmpty()) {
            drawCentered(g, footerText, cx, this.height - 56, footerColor);
        }

        super.extractRenderState(g, mx, my, delta);
    }

    /** Closing this screen goes to the title menu, not whatever was behind it. */
    @Override
    public void onClose() {
        backToMenu();
    }

    /** Never close on ESC during download — the install must complete or fail. */
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

        drawCentered(g, "You'll be disconnected while they download.", cx, y, COL_YELLOW);
        y += 12;
        drawCentered(g, "Conduit will reconnect you automatically.", cx, y, COL_YELLOW);
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

    // ── Actions ───────────────────────────────────────────────────────────────

    private void startDownload() {
        phase = Phase.DOWNLOADING;
        applyPhaseButtons();
        footerText = "";
        // Reset per-mod status for retry runs.
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
                        phase = Phase.DONE;
                        footerText  = "§aAll mods installed. Click Reconnect to rejoin.";
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

    /** Reconnect to the captured server address using the vanilla connect flow. */
    private void reconnect() {
        Optional<ServerAddress> addrOpt = ClientManifestHandler.getPendingServerAddress();
        if (addrOpt.isEmpty()) {
            LOGGER.warn("Reconnect requested but no server address was captured.");
            backToMenu();
            return;
        }
        ServerAddress addr = addrOpt.get();
        String name = ClientManifestHandler.getPendingServerName().orElse(payload.serverName());

        // Build a fresh ServerData so ConnectScreen has what it needs.
        // Type.OTHER covers normal multiplayer and direct connect.
        ServerData data = new ServerData(name, addr.getHost() + ":" + addr.getPort(),
                ServerData.Type.OTHER);

        LOGGER.info("Reconnecting to {} ({})", name, addr);
        ConnectScreen.startConnecting(
                this,                 // parent screen to return to on failure
                minecraft,            // the Minecraft instance
                addr,                 // resolved server address
                data,                 // server metadata
                false,                // direct connect? false -> uses normal connect path
                new TransferState(    // transfer state (empty; fresh connection)
                        java.util.Map.of(),
                        java.util.Map.of(),
                        false)
        );
    }

    private void backToMenu() {
        ClientManifestHandler.clearPending();
        minecraft.setScreen(new TitleScreen());
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
