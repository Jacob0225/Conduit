package com.jacob0225.conduit.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;

import java.net.URI;

/** Optional NeoForge configuration-phase protocol for endpoint discovery. */
public final class PortDiscovery {
    public static final byte READY = 0;
    public static final byte NEEDS_SETUP = 1;
    public static final byte ENDPOINT_UNREACHABLE = 2;
    public static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type(
            ResourceLocation.fromNamespaceAndPath("conduit", "manifest_check"));
    private PortDiscovery() {}

    public record Advertisement(int port, String publicBaseUrl) implements CustomPacketPayload {
        public static final Type<Advertisement> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("conduit", "http_port"));
        public static final StreamCodec<FriendlyByteBuf, Advertisement> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeVarInt(value.port); buf.writeUtf(value.publicBaseUrl, 2048); },
                buf -> new Advertisement(buf.readVarInt(), buf.readUtf(2048)));
        public Advertisement { validate(port, publicBaseUrl); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public Endpoint endpoint() { return new Endpoint(port, publicBaseUrl.trim()); }
    }

    public record Response(byte status) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("conduit", "http_port_response"));
        public static final StreamCodec<FriendlyByteBuf, Response> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeByte(value.status), buf -> new Response(buf.readByte()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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
