package com.jacob0225.conduit.client.ui;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Install screen shown when a join is intercepted and the client is missing
 * required mods. Runs the existing download pipeline, then offers to restart
 * the game so the newly-installed mods are actually loaded by the JVM.
 *
 * <p>Unlike the prior config-phase version, the player has NOT joined yet —
 * this screen appears instead of the connect. On success, "Restart Game"
 * relaunches the process via {@code ProcessHandle}, then stops the current
 * JVM. On the next launch Fabric will load the new mods and the join will
 * proceed without a registry mismatch.
 *
 * <p>Phases:
 *   PROMPT     — "Server requires N mods. Download?"
 *   DOWNLOADING — per-mod progress list, buttons disabled
 *   DONE        — "Installed!" + Restart Game / Back
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
    private final ManifestVerifier.DiffResult diff;
    private final Screen        parent;

    private final List<ModEntry> modList;
    private final List<String>   modStatus;
    private final List<Integer>  modColor;

    private Button primaryButton;   // Yes / (hidden) / Restart Game / Retry
    private Button secondaryButton; // Cancel / Back

    private String footerText  = "";
    private int    footerColor = COL_WHITE;

    public ModReviewScreen(ManifestPayload payload, ManifestVerifier.DiffResult diff,
                           Screen parent) {
        super(Component.literal("Conduit — Mod Sync"));
        this.payload = payload;
        this.diff    = diff;
        this.parent  = parent;

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

        // Primary button action is phase-dependent: download on PROMPT, restart
        // on DONE, retry on ERROR. Dispatching inside the lambda means we only
        // change the label to change behavior — no re-binding needed.
        primaryButton = Button.builder(
                Component.literal("Yes, Download"),
                btn -> {
                    switch (phase) {
                        case PROMPT, ERROR -> startDownload();
                        case DONE          -> restartGame();
                        default            -> { /* disabled during DOWNLOADING */ }
                    }
                }
        ).bounds(cx - 106, by, 100, 20).build();

        secondaryButton = Button.builder(
                Component.literal("Cancel"),
                btn -> backToParent()
        ).bounds(cx + 6, by, 100, 20).build();

        addRenderableWidget(primaryButton);
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
            }
            case DOWNLOADING -> {
                primaryButton.visible = false;
                primaryButton.active = false;
                secondaryButton.setMessage(Component.literal("Cancel"));
                secondaryButton.active = false; // atomic install — no mid-download cancel
            }
            case DONE -> {
                primaryButton.setMessage(Component.literal("Restart Game"));
                primaryButton.visible = true;
                primaryButton.active = true;
                secondaryButton.setMessage(Component.literal("Back"));
                secondaryButton.active = true;
            }
            case ERROR -> {
                primaryButton.setMessage(Component.literal("Retry"));
                primaryButton.visible = true;
                primaryButton.active = true;
                secondaryButton.setMessage(Component.literal("Back"));
                secondaryButton.active = true;
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

        drawCentered(g, "Conduit will download them before you join.", cx, y, COL_YELLOW);
        y += 12;
        drawCentered(g, "The game will restart to load them, then you can join.", cx, y, COL_YELLOW);
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
                        phase = Phase.DONE;
                        footerText  = "§aAll mods installed! Restart the game to load them.";
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
     * Attempts to relaunch the current Minecraft process, then stops this JVM.
     *
     * <p>Fabric mods cannot be hot-loaded: newly-downloaded JARs only become
     * available to the game after a full JVM restart. Simply reconnecting would
     * result in the same registry-mismatch disconnect because the downloaded
     * mods were never loaded.
     *
     * <p>Relaunch strategy: {@code ProcessHandle.current().info()} gives us the
     * exact command + arguments that were used to start this JVM, so we can
     * spawn a new process with the same invocation. This works correctly with
     * most launchers (vanilla, Modrinth, MultiMC, Prism) because they delegate
     * to a plain {@code java} invocation. If the relaunch fails (e.g. the
     * launcher wrapped the process in a way that hides the command), we fall
     * back to a clean stop and show a message telling the player to reopen
     * manually.
     */
    private void restartGame() {
        LOGGER.info("Conduit: restarting Minecraft to load newly-installed mods");

        boolean relaunched = false;
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            java.util.Optional<String> command = info.command();
            java.util.Optional<String[]> arguments = info.arguments();

            if (command.isPresent()) {
                List<String> cmd = new ArrayList<>();
                cmd.add(command.get());
                arguments.ifPresent(args -> cmd.addAll(Arrays.asList(args)));

                LOGGER.info("Relaunching via: {}", command.get());
                new ProcessBuilder(cmd)
                        .inheritIO()
                        .start();
                relaunched = true;
            } else {
                LOGGER.warn("ProcessHandle.info().command() is empty — cannot relaunch automatically.");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to start new game process: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Unexpected error during relaunch attempt: {}", e.getMessage());
        }

        if (!relaunched) {
            // Could not spawn a new process — update the UI to tell the player
            // to reopen the game themselves, then close.
            footerText  = "§eMods installed. Please reopen the launcher to play.";
            footerColor = COL_YELLOW;
            // Give the player a moment to read the message, then close.
            minecraft.execute(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                minecraft.stop();
            });
            return;
        }

        // Relaunch succeeded — stop this JVM immediately.
        minecraft.execute(minecraft::stop);
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
