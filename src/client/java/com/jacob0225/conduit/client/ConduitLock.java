package com.jacob0225.conduit.client;

/**
 * Singleton flag that signals Conduit is currently installing mods.
 *
 * While {@link #isLocked()} returns true:
 *  - Player movement packets are suppressed (via mixin)
 *  - Incoming damage packets are suppressed (via mixin)
 *  - The ModReviewScreen is shown as an overlay over the game world
 *
 * Lock is cleared when installation completes (success or cancel).
 */
public final class ConduitLock {

    private static volatile boolean locked = false;

    private ConduitLock() {}

    public static boolean isLocked() { return locked; }
    public static void lock()        { locked = true;  }
    public static void unlock()      { locked = false; }
}
