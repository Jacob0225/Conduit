package dev.conduit.hotmixin;

import dev.conduit.hotmixin.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.common.Mod;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Each static method exercises one mixin case from the mod's own code, the way a
 * real mod would. The spike harness calls them and checks the results.
 */
@Mod("hotmixin")
public final class HotMixin {
    public static final AtomicInteger LAMBDA_RUNS = new AtomicInteger();
    public static final AtomicInteger TITLE_FRAMES = new AtomicInteger();

    public HotMixin() {}

    /** Interface added to a loaded class + its added method + an added instance field. */
    public static int ticks() {
        return ((TickCounter) Minecraft.getInstance()).hotmixin$ticks();
    }

    /** Added static field with an initializer. */
    public static int staticTicks() {
        return ((TickCounter) Minecraft.getInstance()).hotmixin$staticTicks();
    }

    public static boolean isTickCounter() {
        return Minecraft.getInstance() instanceof TickCounter;
    }

    /** @Accessor getter + setter on a private field. Returns the value read back after writing 7. */
    public static int accessorRoundTrip() {
        MinecraftAccessor acc = (MinecraftAccessor) Minecraft.getInstance();
        int before = acc.hotmixin$getRightClickDelay();
        acc.hotmixin$setRightClickDelay(7);
        int after = acc.hotmixin$getRightClickDelay();
        acc.hotmixin$setRightClickDelay(before);
        return after;
    }

    /** @Invoker on a private method. */
    public static boolean invokerIsMultiplayer() {
        return ((MinecraftAccessor) Minecraft.getInstance()).hotmixin$isMultiplayerServer();
    }

    /** A mixin on a class that loads only after the hot-load uses the normal Mixin path. */
    public static boolean lateTargetMarked() throws ClassNotFoundException {
        Class<?> win = Class.forName("net.minecraft.client.gui.screens.WinScreen");
        return Marker.class.isAssignableFrom(win);
    }
}
