package com.jacob0225.conduit.network;

import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.security.InputValidator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S2C CustomPacketPayload carrying the mod manifest.
 *
 * 26.1 networking: packets implement CustomPacketPayload with a TYPE and a
 * StreamCodec<FriendlyByteBuf, T>. PayloadTypeRegistry.playS2C() is used to
 * register the type before any listener or sender is registered.
 *
 * Security contract: no URLs, file paths, or executable data. All string
 * fields are length-capped in the CODEC's read path.
 */
public record ManifestPayload(
        int schemaVersion,
        int manifestVersion,
        String serverName,
        List<ModEntry> mods
) implements CustomPacketPayload {

    public static final int CURRENT_SCHEMA = 1;
    public static final int MAX_MODS = 256;

    /** The channel identifier — referenced by both server and client. */
    public static final CustomPacketPayload.Type<ManifestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("conduit", "manifest_sync"));

    /**
     * StreamCodec used by PayloadTypeRegistry.
     * encode = writeTo, decode = readFrom (with security validation).
     */
    public static final StreamCodec<FriendlyByteBuf, ManifestPayload> CODEC =
            StreamCodec.of(ManifestPayload::writeTo, ManifestPayload::readFrom);

    public ManifestPayload {
        if (schemaVersion != CURRENT_SCHEMA)
            throw new IllegalArgumentException(
                    "Unsupported manifest schema version: " + schemaVersion);
        InputValidator.requireSafeString("serverName", serverName);
        mods = Collections.unmodifiableList(new ArrayList<>(mods));
    }

    @Override
    public CustomPacketPayload.Type<ManifestPayload> type() {
        return TYPE;
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private static void writeTo(FriendlyByteBuf buf, ManifestPayload payload) {
        buf.writeInt(payload.schemaVersion());
        buf.writeInt(payload.manifestVersion());
        buf.writeUtf(payload.serverName());
        buf.writeInt(payload.mods().size());
        for (ModEntry entry : payload.mods()) {
            entry.writeTo(buf);
        }
    }

    private static ManifestPayload readFrom(FriendlyByteBuf buf) {
        int schemaVersion   = buf.readInt();
        int manifestVersion = buf.readInt();
        String serverName   = buf.readUtf(256);

        int modCount = buf.readInt();
        if (modCount < 0 || modCount > MAX_MODS)
            throw new SecurityException("Manifest mod count out of range: " + modCount);

        List<ModEntry> mods = new ArrayList<>(modCount);
        for (int i = 0; i < modCount; i++) {
            mods.add(ModEntry.readFrom(buf));
        }

        return new ManifestPayload(schemaVersion, manifestVersion, serverName, mods);
    }
}
