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
     * Returns true if the installed version satisfies the required version.
     *
     * Checks exact equality first, then falls back to checking whether either
     * version string contains the other — this handles common Fabric version
     * string variations like "0.6.0+mc26.1" vs "0.6.0+mc26.1+build.100".
     *
     * Also normalises by stripping build metadata after '+' for comparison,
     * so "1.2.3+mc26.1+build.99" is considered equal to "1.2.3+mc26.1".
     */
    public boolean hasSatisfyingVersion(String modId, String requiredVersion) {
        String installed = installedVersions.get(modId);
        if (installed == null) return false;

        // 1. Exact match
        if (installed.equals(requiredVersion)) return true;

        // 2. Strip build metadata (everything after the second '+') and compare
        String normInstalled = stripBuildMeta(installed);
        String normRequired  = stripBuildMeta(requiredVersion);
        if (normInstalled.equals(normRequired)) return true;

        // 3. One contains the other (handles reversed metadata ordering)
        if (installed.contains(requiredVersion) || requiredVersion.contains(installed)) return true;
        if (normInstalled.contains(normRequired) || normRequired.contains(normInstalled)) return true;

        return false;
    }

    /** Strips everything after the second '+' sign (build metadata). */
    private static String stripBuildMeta(String version) {
        int first = version.indexOf('+');
        if (first < 0) return version;
        int second = version.indexOf('+', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }
}
