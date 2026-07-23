package com.jacob0225.conduit.manifest;

import com.jacob0225.conduit.security.InputValidator;

/** Immutable, validated descriptor for one mod in a server manifest. */
public record ModEntry(
        String modId,
        String displayName,
        String requiredVersion,
        boolean required,
        String modrinthProjectId,
        String curseforgeProjectId,
        String hashAlgorithm,
        String expectedHash
) {
    public ModEntry {
        InputValidator.requireModId(modId);
        InputValidator.requireSafeString("displayName", displayName);
        InputValidator.requireVersion(requiredVersion);
        InputValidator.requireModrinthId(modrinthProjectId);
        InputValidator.requireCurseForgeId(curseforgeProjectId);
        InputValidator.requireHashAlgorithm(hashAlgorithm);
        InputValidator.requireHash(hashAlgorithm, expectedHash);

        if (modrinthProjectId.isEmpty() && curseforgeProjectId.isEmpty()) {
            throw new SecurityException(
                    "Mod '" + modId + "' must have at least one platform ID");
        }
    }
}
