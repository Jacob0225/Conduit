package com.jacob0225.conduit.network;

import net.minecraft.network.FriendlyByteBuf;

import java.net.URI;
import java.util.function.IntSupplier;

/** Optional Forge login-phase endpoint discovery messages. */
public final class PortDiscovery {
    public static final byte READY = 0;
    public static final byte NEEDS_SETUP = 1;
    public static final byte ENDPOINT_UNREACHABLE = 2;
    private PortDiscovery() {}

    public static final class Advertisement implements IntSupplier {
        private int loginIndex;
        private final int port;
        private final String publicBaseUrl;
        public Advertisement(int port, String publicBaseUrl) {
            validate(port, publicBaseUrl);
            this.port = port;
            this.publicBaseUrl = publicBaseUrl.trim();
        }
        public static Advertisement decode(FriendlyByteBuf buf) {
            return new Advertisement(buf.readVarInt(), buf.readUtf(2048));
        }
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(port);
            buf.writeUtf(publicBaseUrl, 2048);
        }
        public int loginIndex() { return loginIndex; }
        public void loginIndex(int value) { loginIndex = value; }
        @Override public int getAsInt() { return loginIndex; }
        public Endpoint endpoint() { return new Endpoint(port, publicBaseUrl); }
    }

    public static final class Response implements IntSupplier {
        private int loginIndex;
        private final byte status;
        public Response(byte status) { this.status = status; }
        public static Response decode(FriendlyByteBuf buf) { return new Response(buf.readByte()); }
        public void encode(FriendlyByteBuf buf) { buf.writeByte(status); }
        public byte status() { return status; }
        public int loginIndex() { return loginIndex; }
        public void loginIndex(int value) { loginIndex = value; }
        @Override public int getAsInt() { return loginIndex; }
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
