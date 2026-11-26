package com.jacob0225.conduit.client.devtest;

import com.jacob0225.conduit.client.ui.ConduitButton;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only end-to-end test, active only with {@code -Dconduit.autotest=host:port}. Plays the
 * player's part: join the server, press Download on Conduit's screen, then Load live, then
 * Join server, and report what ended up loaded. Writes {@code conduit-autotest.txt}.
 */
public final class AutoJoinTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/AutoTest");
    private static final List<String> report = new ArrayList<>();
    private static String address;
    private static int titleTicks, worldTicks;
    private static long started, liveStarted;
    private static boolean connected, downloaded, warned, liveLoading, joined, done;

    private AutoJoinTest() {}

    public static void installIfRequested() {
        String value = System.getProperty("conduit.autotest", "");
        if (value.isBlank()) return;
        address = value;
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> { if (e.phase == TickEvent.Phase.END) tick(); });
        LOGGER.info("Conduit auto-test armed for {}", address);
    }

    private static void tick() {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (!connected) {
            if (mc.screen instanceof TitleScreen && ++titleTicks > 40) {
                connected = true;
                started = System.currentTimeMillis();
                report.add("INFO mods loaded at start: " + ModList.get().size());
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                        new ServerData("Conduit test", address, ServerData.Type.OTHER), false, null);
            }
            return;
        }
        if (System.currentTimeMillis() - started > 8 * 60_000) { finish(mc, "FAIL timed out on " + mc.screen); return; }
        if (mc.screen instanceof ModReviewScreen screen) {
            ConduitButton primary = primary(screen);
            if (primary == null || !primary.active) return;
            String label = primary.getMessage().getString();
            if (!downloaded && label.startsWith("Download")) {
                downloaded = true;
                report.add("INFO Conduit screen opened after " + (System.currentTimeMillis() - started) + " ms; pressing " + label);
                primary.onPress();
            } else if (label.equals("Load live") && !warned) {
                warned = true;
                report.add("PASS downloads finished; Conduit offers Load live");
                primary.onPress();
            } else if (label.equals("Start loading") && !liveLoading) {
                liveLoading = true;
                liveStarted = System.currentTimeMillis();
                report.add("PASS freeze warning shown before loading");
                Screenshot.grab(mc.gameDirectory, "conduit-autotest-warning.png", mc.getMainRenderTarget(), m -> {});
                primary.onPress();
            } else if (label.equals("Join server") && !joined) {
                joined = true;
                report.add("PASS loaded live in " + (System.currentTimeMillis() - liveStarted) + " ms (incl. resource reload)");
                Screenshot.grab(mc.gameDirectory, "conduit-autotest-ready.png", mc.getMainRenderTarget(), m -> {});
                primary.onPress();
            } else if (label.startsWith("Restart") || label.startsWith("Close")) {
                finish(mc, "FAIL Conduit fell back to " + label + " (live loading unavailable or failed)");
            }
            return;
        }
        if (mc.player != null && mc.level != null) {
            if (++worldTicks == 1) report.add("PASS joined the server " + (System.currentTimeMillis() - started) + " ms after first connecting");
            if (worldTicks == 80) {
                for (String id : System.getProperty("conduit.autotest.mods", "").split(",")) {
                    if (id.isBlank()) continue;
                    report.add((ModList.get().isLoaded(id) ? "PASS " : "FAIL ") + id + " loaded");
                }
                for (String id : System.getProperty("conduit.autotest.mods", "").split(",")) {
                    if (id.isBlank()) continue;
                    long blocks = BuiltInRegistries.BLOCK.keySet().stream().filter(k -> k.getNamespace().equals(id)).count();
                    long items = BuiltInRegistries.ITEM.keySet().stream().filter(k -> k.getNamespace().equals(id)).count();
                    report.add("INFO " + id + ": " + blocks + " blocks, " + items + " items");
                }
                report.add("INFO mods loaded now: " + ModList.get().size());
                if (mc.screen != null) mc.setScreen(null);
                Screenshot.grab(mc.gameDirectory, "conduit-autotest-ingame.png", mc.getMainRenderTarget(), m -> {});
            }
            if (worldTicks == 90) finish(mc, null);
        }
    }

    private static ConduitButton primary(ModReviewScreen screen) {
        for (var child : screen.children()) {
            if (child instanceof ConduitButton b && b.visible) {
                String label = b.getMessage().getString();
                if (label.startsWith("Download") || label.equals("Load live") || label.equals("Start loading") || label.equals("Join server")
                        || label.startsWith("Restart") || label.startsWith("Close") || label.startsWith("Loading")
                        || label.startsWith("Installing")) return b;
            }
        }
        return null;
    }

    private static void finish(Minecraft mc, String last) {
        done = true;
        if (last != null) report.add(last);
        String text = String.join(System.lineSeparator(), report);
        LOGGER.info("AUTOTEST REPORT\n{}", text);
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("conduit-autotest.txt"), text + System.lineSeparator());
        } catch (Exception e) {
            LOGGER.warn("Could not write report", e);
        }
        mc.stop();
    }
}
