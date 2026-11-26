package com.jacob0225.conduit.network;

import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Invisible server-list status marker understood by Conduit clients. Besides saying "this is a
 * Conduit server", it carries the manifest endpoint, so a client can check the server's mods
 * before it joins (some mods' required network channels end the join before Conduit's in-login
 * check would run).
 */
public final class ConduitStatusMarker {
    private static final String TOKEN = "conduit:server";
    private static final String ENDPOINT_PREFIX = "conduit:http|";
    private static volatile boolean available;
    private static volatile String endpointToken;

    private ConduitStatusMarker() {}

    public static Component appendTo(Component motd) {
        if (!available) return motd;
        if (isPresent(motd)) return motd;
        Component marked = motd.copy().append(Component.empty().withStyle(style -> style.withInsertion(TOKEN)));
        String endpoint = endpointToken;
        if (endpoint != null) {
            marked = marked.copy().append(Component.empty().withStyle(style -> style.withInsertion(endpoint)));
        }
        return marked;
    }

    public static void setAvailable(boolean value) {
        available = value;
    }

    /** The manifest endpoint to advertise in the status ping; port 0 or less clears it. */
    public static void setEndpoint(int port, String publicBaseUrl) {
        endpointToken = port > 0 ? ENDPOINT_PREFIX + port + "|" + (publicBaseUrl == null ? "" : publicBaseUrl.trim()) : null;
    }

    public static boolean isPresent(Component component) {
        if (TOKEN.equals(component.getStyle().getInsertion())) return true;
        for (Component sibling : component.getSiblings()) {
            if (isPresent(sibling)) return true;
        }
        return false;
    }

    /** The manifest endpoint from a status message, if it has a valid one. */
    public static Optional<PortDiscovery.Endpoint> endpoint(Component component) {
        if (component == null) return Optional.empty();
        String insertion = component.getStyle().getInsertion();
        if (insertion != null && insertion.startsWith(ENDPOINT_PREFIX)) {
            String[] parts = insertion.substring(ENDPOINT_PREFIX.length()).split("\\|", 2);
            try {
                int port = Integer.parseInt(parts[0]);
                String url = parts.length > 1 ? parts[1] : "";
                return Optional.of(new PortDiscovery.Advertisement(port, url).endpoint()); // validates
            } catch (RuntimeException invalid) {
                return Optional.empty();
            }
        }
        for (Component sibling : component.getSiblings()) {
            Optional<PortDiscovery.Endpoint> found = endpoint(sibling);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }
}
