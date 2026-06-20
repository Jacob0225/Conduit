package com.jacob0225.conduit.client.download;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshot of currently installed mods, built from the Fabric loader's
 * mod container list at the time Conduit processes a manifest.
 *
 * Note: This reflects the mods loaded in the *current* game session.
 * Newly downloaded mods won't appear here until the game restarts.
 */
public class InstalledModIndex {

    /** modId → installed version string */
    private final Map<String, String> installedVersions;

    public InstalledModIndex() {
        installedVersions = new HashMap<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String id      = container.getMetadata().getId();
            String version = container.getMetadata().getVersion().getFriendlyString();
            installedVersions.put(id, version);
        }
    }

    /** Returns true if the given modId is currently loaded (any version). */
    public boolean isInstalled(String modId) {
        return installedVersions.containsKey(modId);
    }

    /** Returns the installed version for the given modId, or empty if not installed. */
    public Optional<String> getInstalledVersion(String modId) {
        return Optional.ofNullable(installedVersions.get(modId));
    }

    /**
     * Returns true if the given modId is installed at exactly the required version.
     *
     * For more sophisticated semver range matching, integrate a semver library.
     * This simple equality check is a safe, conservative starting point.
     */
    public boolean hasSatisfyingVersion(String modId, String requiredVersion) {
        String installed = installedVersions.get(modId);
        return installed != null && installed.equals(requiredVersion);
    }
}
