package com.jacob0225.conduit.client.download;

import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a received {@link ManifestPayload} against the {@link InstalledModIndex}
 * and produces a list of mods that need to be downloaded.
 */
public class ManifestVerifier {

    /** Result of a manifest diff operation. */
    public record DiffResult(
            List<ModEntry> missing,   // not installed at all
            List<ModEntry> outdated,  // installed but wrong version
            List<ModEntry> satisfied  // already correct
    ) {
        public boolean hasWork() {
            return !missing.isEmpty() || !outdated.isEmpty();
        }

        public List<ModEntry> allNeeded() {
            List<ModEntry> all = new ArrayList<>(missing);
            all.addAll(outdated);
            return all;
        }
    }

    private final InstalledModIndex index;

    public ManifestVerifier(InstalledModIndex index) {
        this.index = index;
    }

    /**
     * Performs the diff.
     *
     * Only {@code required == true} mods in the manifest are considered
     * for automatic download. Optional mods are included in the result
     * but flagged separately (callers can choose to surface them in the UI).
     */
    public DiffResult diff(ManifestPayload payload) {
        List<ModEntry> missing   = new ArrayList<>();
        List<ModEntry> outdated  = new ArrayList<>();
        List<ModEntry> satisfied = new ArrayList<>();

        for (ModEntry entry : payload.mods()) {
            if (!entry.required()) {
                // Optional — still report them but don't force download
                // The UI (ModReviewScreen) can let the user opt in
                continue;
            }

            if (!index.isInstalled(entry.modId())) {
                missing.add(entry);
            } else if (!index.hasSatisfyingVersion(entry.modId(), entry.requiredVersion())) {
                outdated.add(entry);
            } else {
                satisfied.add(entry);
            }
        }

        return new DiffResult(missing, outdated, satisfied);
    }
}
