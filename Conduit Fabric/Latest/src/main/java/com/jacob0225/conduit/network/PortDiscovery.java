package com.jacob0225.conduit.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.net.URI;

/** Login-query protocol used to advertise the server's configured HTTP port. */
public final class PortDiscovery {
    public static final Identifier CHANNEL =
            Identifier.fromNamespaceAndPath("conduit", "http_port");

    public static final byte READY = 0;
    public static final byte NEEDS_SETUP = 1;
    public static final byte ENDPOINT_UNREACHABLE = 2;

    private PortDiscovery() {}

    public static FriendlyByteBuf advertisement(int port, String publicBaseUrl) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(port);
        buf.writeUtf(publicBaseUrl, 2048);
        return buf;
    }

    public static Endpoint readEndpoint(FriendlyByteBuf buf) {
        int port = buf.readVarInt();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Conduit HTTP port is out of range: " + port);
        }
        String publicBaseUrl = buf.readUtf(2048).trim();
        if (!publicBaseUrl.isEmpty()) {
            URI uri = URI.create(publicBaseUrl);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Conduit publicBaseUrl is not a safe HTTP(S) base URL");
            }
        }
        return new Endpoint(port, publicBaseUrl);
    }

    public static FriendlyByteBuf response(byte status) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(status);
        return buf;
    }

    public record Endpoint(int port, String publicBaseUrl) {}
}
