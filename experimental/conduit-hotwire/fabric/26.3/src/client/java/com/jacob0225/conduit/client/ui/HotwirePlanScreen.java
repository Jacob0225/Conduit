package com.jacob0225.conduit.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shows every line of a Hotwire change plan at once (wrapped, never
 * truncated, scrollable) with a single Apply / Cancel decision. Replaces the
 * previous chain of one ConfirmScreen per plan line.
 */
public class HotwirePlanScreen extends Screen {

    private record Line(ConduitUi.Icon icon, int color, List<FormattedCharSequence> wrapped) {}

    private final String serverName;
    private final List<String> planLines;
    private final Consumer<Boolean> decision;
    private final List<Line> lines = new ArrayList<>();
    private boolean decided;
    private boolean pageSoundPlayed;
    private int scroll;
    private int contentHeight;
    private int viewTop;
    private int viewBottom;

    public HotwirePlanScreen(Screen parent, String serverName, List<String> planLines, Consumer<Boolean> decision) {
        super(Component.literal("Conduit + Hotwire - Review plan"));
        this.serverName = serverName == null ? "" : serverName;
        this.planLines = List.copyOf(planLines);
        this.decision = decision;
    }

    private int panelWidth() { return Math.min(this.width - 16, 400); }
    private int panelHeight() { return Math.min(this.height - 16, 250); }
    private int left() { return this.width / 2 - panelWidth() / 2; }
    private int top() { return this.height / 2 - panelHeight() / 2; }

    @Override
    protected void init() {
        if (lines.isEmpty() || this.width != lastWidth) rewrap();
        int l = left();
        int b = top() + panelHeight();
        int gap = 6;
        int each = Math.min(140, (panelWidth() - 28 - gap) / 2);
        int x = this.width / 2 - (each * 2 + gap) / 2;
        addRenderableWidget(new ConduitButton(x, b - 30, each, 20, Component.literal("Apply changes"),
                ConduitButton.Style.PRIMARY, btn -> decide(true))
                .tooltip("Hotwire applies these changes to the running game."));
        addRenderableWidget(new ConduitButton(x + each + gap, b - 30, each, 20, Component.literal("Cancel"),
                ConduitButton.Style.SECONDARY, btn -> decide(false)));
        if (!pageSoundPlayed) {
            pageSoundPlayed = true;
            ConduitSounds.page();
        }
    }

    private int lastWidth = -1;

    private void rewrap() {
        lastWidth = this.width;
        lines.clear();
        int wrapWidth = panelWidth() - 24 - 16 - 8;
        List<String> source = planLines.isEmpty() ? List.of("Hotwire reported no individual changes.") : planLines;
        for (String raw : source) {
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            ConduitUi.Icon icon;
            int color;
            if (lower.contains("turns off") || lower.contains("remove") || lower.contains("unload")) {
                icon = ConduitUi.Icon.MINUS; color = ConduitUi.RED;
            } else if (lower.contains("turns on") || lower.contains("enable")) {
                icon = ConduitUi.Icon.PLUS; color = ConduitUi.GREEN;
            } else if (lower.contains("add") || lower.contains("load") || lower.contains("update")
                    || lower.contains(" is in")) {
                icon = ConduitUi.Icon.ARROW; color = ConduitUi.ACCENT;
            } else if (lower.contains("restart") || lower.contains("can't") || lower.contains("cannot")
                    || lower.contains("blocked") || lower.contains("warning")) {
                icon = ConduitUi.Icon.WARN; color = ConduitUi.YELLOW;
            } else {
                icon = ConduitUi.Icon.DOT; color = ConduitUi.MUTED;
            }
            lines.add(new Line(icon, color, ConduitUi.font().split(Component.literal(raw), Math.max(60, wrapWidth))));
        }
    }

    private void decide(boolean accepted) {
        if (decided) return;
        decided = true;
        decision.accept(accepted);
    }

    @Override
    public void onClose() {
        ConduitSounds.press(ConduitSounds.Press.BACK);
        decide(false);
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
        ConduitUi.text(g, Component.literal("Review live changes").withStyle(ChatFormatting.BOLD), l + 46, t + 12,
                ConduitUi.TEXT);
        String count = planLines.size() + (planLines.size() == 1 ? " CHANGE" : " CHANGES");
        int pillW = ConduitUi.pill(g, count, r - 10, t + 11, ConduitUi.GOLD, true);
        ConduitUi.text(g, ConduitUi.trim("Hotwire will switch the running game to " + serverName,
                r - 16 - pillW - (l + 46)), l + 46, t + 25, ConduitUi.MUTED);
        ConduitUi.divider(g, l + 10, r - 10, t + 44);

        viewTop = t + 52;
        viewBottom = b - 40;
        int lineH = 10;
        contentHeight = 0;
        for (Line line : lines) contentHeight += line.wrapped().size() * lineH + 4;
        int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        ConduitUi.roundRect(g, l + 10, viewTop - 3, r - 10, viewBottom + 1, 0xFF0A141E);
        g.enableScissor(l + 11, viewTop - 2, r - 11, viewBottom);
        int y = viewTop + 1 - scroll;
        for (Line line : lines) {
            ConduitUi.icon(g, line.icon(), l + 18, y, line.color());
            for (FormattedCharSequence seq : line.wrapped()) {
                ConduitUi.text(g, seq, l + 32, y, ConduitUi.TEXT);
                y += lineH;
            }
            y += 4;
        }
        g.disableScissor();

        if (maxScroll > 0) {
            int track = viewBottom - viewTop;
            int thumb = Math.max(12, track * track / contentHeight);
            int thumbY = viewTop + (track - thumb) * scroll / maxScroll;
            ConduitUi.roundRect(g, r - 15, thumbY, r - 12, thumbY + thumb, ConduitUi.ACCENT);
        }

        super.extractRenderState(g, mx, my, delta);
        ConduitParticles.render(g);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        if (maxScroll > 0 && verticalAmount != 0.0D) {
            int next = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(verticalAmount) * 12));
            if (next != scroll) {
                scroll = next;
                ConduitSounds.scroll();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
