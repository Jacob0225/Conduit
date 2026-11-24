package com.jacob0225.conduit.network;

import net.minecraft.network.chat.Component;

/** Invisible server-list status marker understood by Conduit clients. */
public final class ConduitStatusMarker {
    private static final String TOKEN = "conduit:server";
    private static volatile boolean available;

    private ConduitStatusMarker() {}

    public static Component appendTo(Component motd) {
        if (!available) return motd;
        if (isPresent(motd)) return motd;
        return motd.copy().append(Component.empty().withStyle(style -> style.withInsertion(TOKEN)));
    }

    public static void setAvailable(boolean value) {
        available = value;
    }

    public static boolean isPresent(Component component) {
        if (TOKEN.equals(component.getStyle().getInsertion())) return true;
        for (Component sibling : component.getSiblings()) {
            if (isPresent(sibling)) return true;
        }
        return false;
    }
}
