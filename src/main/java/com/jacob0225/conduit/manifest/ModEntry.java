package com.jacob0225.conduit.manifest;

import com.jacob0225.conduit.security.InputValidator;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Immutable descriptor for one mod entry in the server manifest.
 *
 * All fields are validated at construction time via {@link InputValidator}.
 * This class is the single source of truth for what the server is allowed
 * to communicate to the client about a mod.
 *
 * NOTE: No URL, no file path, no executable content is stored here.
 */
public record ModEntry(
        String modId,
        String displayName,
        String requiredVersion,
        boolean required,
        String modrinthProjectId,   // may be empty string
        String curseforgeProjectId, // may be empty string
        String hashAlgorithm,
        String expectedHash         // lowercase hex
) {

    public ModEntry {
        // Validate every field on construction — this is the security gate
        InputValidator.requireModId(modId);
        InputValidator.requireSafeString("displayName", displayName);
        InputValidator.requireVersion(requiredVersion);
        InputValidator.requireModrinthId(modrinthProjectId);
        InputValidator.requireCurseForgeId(curseforgeProjectId);
        InputValidator.requireHashAlgorithm(hashAlgorithm);
        InputValidator.requireHash(hashAlgorithm, expectedHash);

        if (modrinthProjectId.isEmpty() && curseforgeProjectId.isEmpty())
            throw new SecurityException(
                    "Mod '" + modId + "' must have at least one platform ID");
    }

    // ── Network serialization ─────────────────────────────────────────────────

    public void writeTo(FriendlyByteBuf buf) {
        buf.writeUtf(modId);
        buf.writeUtf(displayName);
        buf.writeUtf(requiredVersion);
        buf.writeBoolean(required);
        buf.writeUtf(modrinthProjectId);
        buf.writeUtf(curseforgeProjectId);
        buf.writeUtf(hashAlgorithm);
        buf.writeUtf(expectedHash);
    }

    /**
     * Reads and validates a ModEntry from the network.
     * Throws {@link SecurityException} if any field fails validation.
     */
    public static ModEntry readFrom(FriendlyByteBuf buf) {
        // readUtf(256) caps the field at 256 bytes — prevents oversized-string attacks
        String modId             = buf.readUtf(64);
        String displayName       = buf.readUtf(256);
        String requiredVersion   = buf.readUtf(64);
        boolean required         = buf.readBoolean();
        String modrinthId        = buf.readUtf(16);
        String curseforgeId      = buf.readUtf(16);
        String hashAlgorithm     = buf.readUtf(16);
        String expectedHash      = buf.readUtf(256);

        // Constructor validates everything — will throw SecurityException on bad data
        return new ModEntry(
                modId, displayName, requiredVersion, required,
                modrinthId, curseforgeId, hashAlgorithm, expectedHash
        );
    }
}
