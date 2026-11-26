package dev.conduit.hotmixin;

/** Added to Minecraft by MinecraftMixin (an interface added to an already-loaded class). */
public interface TickCounter {
    int hotmixin$ticks();
    int hotmixin$staticTicks();
}
