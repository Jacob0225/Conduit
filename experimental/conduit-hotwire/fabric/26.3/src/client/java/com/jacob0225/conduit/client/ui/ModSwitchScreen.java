package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.util.InstanceRestarter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Confirmation screen shown when the player is switching to a server whose
 * previously installed mod set has been restored from the Conduit stash.
 */
public class ModSwitchScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Switch");

    private final String serverName;
    private final Screen parent;
    private final ServerAddress address;
    private final ServerData serverData;
    private final java.util.List<String> modSet;
    private boolean openSoundPlayed;

    public ModSwitchScreen(String serverName, Screen parent, ServerAddress address, ServerData serverData) {
        this(serverName, parent, address, serverData, java.util.List.of());
    }

    /** @param modSet the jars this server's restored mod set contains, listed on the restart screen */
    public ModSwitchScreen(String serverName, Screen parent, ServerAddress address, ServerData serverData,
                           java.util.Collection<String> modSet) {
        super(Component.literal("Conduit - Server Switch"));
        this.serverName = serverName != null && !serverName.isBlank() ? serverName : "this server";
        this.parent = parent;
        this.address = address;
        this.serverData = serverData;
        this.modSet = modSet.stream().map(n -> n.endsWith(".jar") ? n.substring(0, n.length() - 4) : n)
                .sorted().toList();
    }

    private int panelWidth() { return Math.min(this.width - 16, 400); }
    private int panelHeight() { return Math.min(this.height - 16, 196); }
    private int left() { return this.width / 2 - panelWidth() / 2; }
    private int top() { return this.height / 2 - panelHeight() / 2; }

    @Override
    protected void init() {
        int b = top() + panelHeight();
        int gap = 6;
        int each = Math.min(124, (panelWidth() - 28 - gap * 2) / 3);
        int x = this.width / 2 - (each * 3 + gap * 2) / 2;
        boolean relaunch = InstanceRestarter.canRelaunch();

        addRenderableWidget(new ConduitButton(x, b - 30, each, 20,
                Component.literal(relaunch ? "Restart & join" : "Close game"), ConduitButton.Style.PRIMARY, btn -> {
            LOGGER.info("Player chose restart for server switch to '{}'", serverName);
            String reconnect = address == null ? null
                    : com.jacob0225.conduit.restart.LaunchCommand.address(address.getHost(), address.getPort());
            minecraft.setScreenAndShow(new RestartScreen(this, serverName, modSet, List.of(), reconnect, null));
        }).tooltip(relaunch ? "Restart Minecraft with this server's mods, then reconnect."
                : "Minecraft closes. Start it again from your launcher, then reconnect."));

        ConduitButton join = new ConduitButton(x + each + gap, b - 30, each, 20, Component.literal("Join anyway"),
                ConduitButton.Style.WARNING, btn -> joinAnyway())
                .tooltip("Join with the mods that are loaded right now. The server may disconnect you.");
        join.active = address != null;
        addRenderableWidget(join);

        addRenderableWidget(new ConduitButton(x + (each + gap) * 2, b - 30, each, 20, Component.literal("Back"),
                ConduitButton.Style.SECONDARY, btn -> minecraft.setScreenAndShow(parent)));

        if (!openSoundPlayed) {
            openSoundPlayed = true;
            ConduitSounds.open();
        }
    }

    /**
     * Previously this only closed the screen (back to the server list) despite
     * its label. The stash is already reconciled, so a fresh connection passes
     * Conduit's check and proceeds with the currently loaded mods.
     */
    private void joinAnyway() {
        if (address == null) {
            minecraft.setScreenAndShow(parent);
            return;
        }
        LOGGER.info("Player chose Join anyway for '{}' - skipping swap restart", serverName);
        ConnectScreen.startConnecting(parent != null ? parent : this, minecraft, address, serverData, false, null);
    }

    @Override
    public void onClose() {
        ConduitSounds.press(ConduitSounds.Press.BACK);
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
        ConduitUi.text(g, Component.literal("Switching servers").withStyle(ChatFormatting.BOLD), l + 46, t + 12,
                ConduitUi.TEXT);
        g.fill(l + 46, t + 27, l + 49, t + 30, ConduitUi.ACCENT);
        ConduitUi.text(g, ConduitUi.trim(serverName, r - l - 120), l + 53, t + 25, ConduitUi.MUTED);
        ConduitUi.pill(g, "RESTART", r - 10, t + 11, ConduitUi.GOLD, true);
        ConduitUi.divider(g, l + 10, r - 10, t + 44);

        int y = t + 54;
        int wrap = r - l - 44;
        y = paragraph(g, ConduitUi.Icon.CHECK, ConduitUi.GREEN,
                "Conduit restored this server's saved mod set into your mods folder.", l + 14, y, wrap);
        y = paragraph(g, ConduitUi.Icon.INFO, ConduitUi.ACCENT, InstanceRestarter.canRelaunch()
                ? "Restart Minecraft so the mod loader picks up the new jars, then reconnect."
                : "Close Minecraft and start it again from your launcher, then reconnect.", l + 14, y, wrap);
        paragraph(g, ConduitUi.Icon.WARN, ConduitUi.YELLOW,
                "Join anyway uses the mods loaded right now and may be disconnected.", l + 14, y, wrap);

        super.extractRenderState(g, mx, my, delta);
        ConduitParticles.render(g);
    }

    private int paragraph(GuiGraphicsExtractor g, ConduitUi.Icon icon, int color, String text, int x, int y, int wrap) {
        List<FormattedCharSequence> lines = ConduitUi.font().split(Component.literal(text), wrap);
        ConduitUi.icon(g, icon, x + 2, y + 1, color);
        for (FormattedCharSequence line : lines) {
            ConduitUi.text(g, line, x + 16, y, ConduitUi.TEXT);
            y += 10;
        }
        return y + 6;
    }
}
