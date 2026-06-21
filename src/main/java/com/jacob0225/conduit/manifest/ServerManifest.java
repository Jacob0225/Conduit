package com.jacob0225.conduit.manifest;

import java.util.List;

/**
 * POJO for GSON deserialization of {@code config/conduit/manifest.json}.
 *
 * Validation happens in {@link ServerManifestProvider}, not here.
 * These fields are package-private intentionally — only the provider touches them.
 */
public class ServerManifest {

    int schemaVersion;
    int manifestVersion;
    String serverName;
    List<ModEntryJson> mods;

    /** Raw JSON shape for one mod — mirrors {@link ModEntry} but without validation. */
    public static class ModEntryJson {
        public String modId;
        public String displayName;
        public String requiredVersion;
        public boolean required;
        public String modrinthProjectId;
        public String curseforgeProjectId;
        public String hashAlgorithm;
        public String expectedHash;
    }
}

/**
 * Schema version 2 — simplified manifest where the server resolves mod details
 * from Modrinth at startup. The operator only provides a slug and a
 * required flag; everything else (name, version, hash) is auto-filled.
 */
class ServerManifestV2 {

    int schemaVersion;
    int manifestVersion;
    String serverName;
    List<SimpleEntry> mods;

    /** One entry the operator needs to write — just a slug and optional override. */
    static class SimpleEntry {
        /** Modrinth slug (e.g. "sodium") or short ID (e.g. "AANobbMI"). */
        public String slug;

        /**
         * Optional Fabric mod ID override. If null/empty, the slug is used
         * as the mod ID. Provide this when the Modrinth slug differs from the
         * mod's fabric.mod.json id.
         */
        public String modId;

        /** Whether the mod must be installed to join. Defaults to true. */
        public boolean required = true;
    }
}
