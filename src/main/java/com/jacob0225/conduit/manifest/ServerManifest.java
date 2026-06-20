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
