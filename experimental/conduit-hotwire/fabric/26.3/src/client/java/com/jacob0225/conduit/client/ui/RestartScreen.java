package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.util.InstanceRestarter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks before restarting: shows what changes, offers to rejoin the server,
 * and only then restarts. "Restart later" changes nothing.
 */
public class RestartScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Restart");
    private static final int MAX_LINES = 8;

    /** Work that must happen right before the restart (e.g. swapping jars). May throw. */
    public interface Preparation {
        void run() throws Exception;
    }

    private final Screen parent;
    private final String serverName;
    private final List<String> added;
    private final List<String> removed;
    private final String reconnect;
    private final Preparation prepare;
    private final boolean relaunch = InstanceRestarter.canRelaunch();
    private boolean rejoin = true;
    private String error;
    private boolean openSoundPlayed;

    /**
     * @param added     mods that will load after the restart
     * @param removed   mods that will be put away
     * @param reconnect {@code host:port} to offer rejoining, or null
     */
    public RestartScreen(Screen parent, String serverName, List<String> added, List<String> removed,
                         String reconnect, Preparation prepare) {
        super(Component.literal("Conduit - Restart"));
        this.parent = parent;
        this.serverName = serverName == null || serverName.isBlank() ? "this server" : serverName;
        this.added = List.copyOf(added);
        this.removed = List.copyOf(removed);
        this.reconnect = reconnect;
        this.prepare = prepare;
    }

    private int panelWidth() { return Math.min(this.width - 16, 380); }
    private int panelHeight() { return Math.min(this.height - 16, 118 + listHeight() + (showRejoin() ? 32 : 0)); }
    private int left() { return this.width / 2 - panelWidth() / 2; }
    private int top() { return this.height / 2 - panelHeight() / 2; }
    private boolean showRejoin() { return relaunch && reconnect != null; }

    private int listHeight() {
        int lines = Math.min(MAX_LINES, added.size() + removed.size());
        return lines == 0 ? 12 : lines * 11 + 4;
    }

    @Override
    protected void init() {
        int l = left();
        int b = top() + panelHeight();
        if (showRejoin()) {
            addRenderableWidget(new ConduitToggle(l + 12, b - 62, panelWidth() - 24, 24,
                    "Rejoin " + ConduitUi.trim(serverName, 150), "Connect again once Minecraft has loaded",
                    rejoin, (toggle, value) -> rejoin = value));
        }
        int gap = 6;
        int each = Math.min(150, (panelWidth() - 24 - gap) / 2);
        int x = this.width / 2 - (each * 2 + gap) / 2;
        addRenderableWidget(new ConduitButton(x, b - 30, each, 20,
                Component.literal(relaunch ? "Restart Minecraft" : "Close game"), ConduitButton.Style.PRIMARY,
                btn -> confirm()).tooltip(relaunch ? "Minecraft closes and opens again with the new mods."
                : "This launcher can't be restarted automatically. Reopen Minecraft yourself."));
        addRenderableWidget(new ConduitButton(x + each + gap, b - 30, each, 20, Component.literal("Restart later"),
                ConduitButton.Style.SECONDARY, btn -> onClose())
                .tooltip("Keep playing. The new mods load the next time you start Minecraft."));
        if (!openSoundPlayed) {
            openSoundPlayed = true;
            ConduitSounds.open();
        }
    }

    private void confirm() {
        try {
            if (prepare != null) prepare.run();
        } catch (Exception e) {
            LOGGER.warn("Could not prepare the mods folder for restart", e);
            error = "Couldn't prepare the mods folder. Nothing was restarted; see logs/latest.log.";
            ConduitSounds.failure();
            return;
        }
        InstanceRestarter.Result result = InstanceRestarter.restart(rejoin && showRejoin() ? reconnect : null);
        if (result == InstanceRestarter.Result.FAILED) {
            error = "Couldn't start the restart helper. Reopen Minecraft yourself to load the mods.";
            ConduitSounds.failure();
        }
    }

    @Override
    public void onClose() {
        ConduitSounds.close();
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        ConduitUi.backdrop(g, this.width, this.height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int l = left();
        int t = top();
        int r = l + panelWidth();
        int b = t + panelHeight();
        ConduitUi.panel(g, l, t, r, b);
        ConduitUi.emblem(g, l + 26, t + 24, 22, false, ConduitUi.ACCENT);
        ConduitUi.text(g, Component.literal(relaunch ? "Restart Minecraft" : "Reopen Minecraft")
                .withStyle(ChatFormatting.BOLD), l + 46, t + 12, ConduitUi.TEXT);
        ConduitUi.text(g, ConduitUi.trim(serverName, r - l - 130), l + 46, t + 25, ConduitUi.MUTED);
        ConduitUi.pill(g, "RESTART", r - 10, t + 11, ConduitUi.GOLD, true);
        ConduitUi.divider(g, l + 10, r - 10, t + 44);

        int y = t + 52;
        List<Line> lines = lines();
        if (lines.isEmpty()) {
            ConduitUi.text(g, "This server's saved mod set loads after the restart.", l + 14, y, ConduitUi.MUTED);
            y += 12;
        } else {
            for (Line line : lines) {
                ConduitUi.icon(g, line.icon, l + 16, y + 1, line.color);
                ConduitUi.text(g, ConduitUi.trim(line.text, r - l - 44), l + 30, y, ConduitUi.TEXT);
                y += 11;
            }
            y += 4;
        }

        y += 4;
        String note = error != null ? error : relaunch
                ? "Minecraft closes and opens again. Anything unsaved is lost."
                : "Minecraft closes. Start it again from your launcher.";
        int color = error != null ? ConduitUi.RED : ConduitUi.YELLOW;
        for (FormattedCharSequence seq : ConduitUi.font().split(Component.literal(note), r - l - 44)) {
            ConduitUi.text(g, seq, l + 30, y, color);
            y += 10;
        }
        ConduitUi.icon(g, ConduitUi.Icon.WARN, l + 16, t + 57 + listHeight(), color);

        super.extractRenderState(g, mx, my, delta);
        ConduitParticles.render(g);
    }

    private record Line(ConduitUi.Icon icon, int color, String text) {}

    private List<Line> lines() {
        List<Line> out = new ArrayList<>();
        for (String name : added) out.add(new Line(ConduitUi.Icon.PLUS, ConduitUi.GREEN, name));
        for (String name : removed) out.add(new Line(ConduitUi.Icon.MINUS, ConduitUi.MUTED, name + " (put away)"));
        if (out.size() > MAX_LINES) {
            int hidden = out.size() - (MAX_LINES - 1);
            out = new ArrayList<>(out.subList(0, MAX_LINES - 1));
            out.add(new Line(ConduitUi.Icon.DOT, ConduitUi.DIM, "and " + hidden + " more"));
        }
        return out;
    }
}
