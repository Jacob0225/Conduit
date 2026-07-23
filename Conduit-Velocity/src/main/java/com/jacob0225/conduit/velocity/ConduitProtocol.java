package com.jacob0225.conduit.velocity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class ConduitProtocol {
    static final byte READY = 0;
    static final byte NEEDS_SETUP = 1;
    static final byte ENDPOINT_UNREACHABLE = 2;

    private ConduitProtocol() {}

    static byte[] advertisement(int port, String publicBaseUrl) {
        byte[] url = publicBaseUrl.getBytes(StandardCharsets.UTF_8);
        if (url.length > 2048) {
            throw new IllegalArgumentException("publicBaseUrl exceeds 2048 UTF-8 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(url.length + 8);
        writeVarInt(out, port);
        writeVarInt(out, url.length);
        out.writeBytes(url);
        return out.toByteArray();
    }

    static byte responseStatus(byte[] response) {
        if (response == null || response.length == 0) return READY;
        byte status = response[0];
        return status == NEEDS_SETUP || status == ENDPOINT_UNREACHABLE ? status : READY;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        do {
            int part = value & 0x7F;
            value >>>= 7;
            if (value != 0) part |= 0x80;
            out.write(part);
        } while (value != 0);
    }
}
