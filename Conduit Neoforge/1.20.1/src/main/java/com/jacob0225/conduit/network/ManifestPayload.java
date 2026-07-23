package com.jacob0225.conduit.network;

import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.security.InputValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable manifest transferred by Conduit's out-of-band HTTP endpoint. */
public record ManifestPayload(
        int schemaVersion,
        int manifestVersion,
        String serverName,
        List<ModEntry> mods
) {
    public static final int CURRENT_SCHEMA = 1;
    public static final int MAX_MODS = 256;

    public ManifestPayload {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException(
                    "Unsupported manifest schema version: " + schemaVersion);
        }
        InputValidator.requireSafeString("serverName", serverName);
        if (mods == null || mods.size() > MAX_MODS) {
            throw new SecurityException("Manifest mod count out of range");
        }
        mods = Collections.unmodifiableList(new ArrayList<>(mods));
    }
}
