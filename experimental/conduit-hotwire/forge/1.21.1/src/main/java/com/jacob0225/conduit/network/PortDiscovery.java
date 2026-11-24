package com.jacob0225.conduit.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.network.ConfigurationTask;

import java.net.URI;

/** Optional Forge configuration-phase endpoint discovery messages. */
public final class PortDiscovery {
    public static final byte READY = 0;
    public static final byte NEEDS_SETUP = 1;
    public static final byte ENDPOINT_UNREACHABLE = 2;
    public static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type("conduit:manifest_check");
    private PortDiscovery() {}

    public record Advertisement(int port, String publicBaseUrl) {
        public Advertisement { validate(port, publicBaseUrl); }
        public static Advertisement decode(FriendlyByteBuf buf) {
            return new Advertisement(buf.readVarInt(), buf.readUtf(2048));
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(port);
            buf.writeUtf(publicBaseUrl, 2048);
        }
        public Endpoint endpoint() { return new Endpoint(port, publicBaseUrl.trim()); }
    }

    public record Response(byte status) {
        public static Response decode(FriendlyByteBuf buf) { return new Response(buf.readByte()); }
        public void encode(FriendlyByteBuf buf) { buf.writeByte(status); }
    }

    private static void validate(int port, String baseUrl) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("HTTP port is out of range: " + port);
        String value = baseUrl.trim();
        if (value.isEmpty()) return;
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("publicBaseUrl is not a safe HTTP(S) base URL");
        }
    }
    public record Endpoint(int port, String publicBaseUrl) {}
}
