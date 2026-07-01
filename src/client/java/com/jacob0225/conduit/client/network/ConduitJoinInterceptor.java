package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.download.InstalledModIndex;
import com.jacob0225.conduit.client.download.ManifestVerifier;
import com.jacob0225.conduit.client.http.ManifestHttpClient;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the pre-join manifest check.
 *
 * <p>Called by {@code MixinConnectScreen} when the player clicks Join. The flow:
 *   <ol>
 *     <li>Fetch the manifest from the server's HTTP endpoint (out-of-band).
 *     <li>Diff it against installed mods (both FabricLoader and disk scan).
 *     <li>If all satisfied → run {@code proceed} (the captured vanilla connect).
 *     <li>If missing/outdated → show {@link ModReviewScreen}; on successful
 *         install it restarts the game so the new mods are loaded. On next
 *         launch the diff will pass and the player joins normally.
 *   </ol>
 */
public final class ConduitJoinInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Join");

    private ConduitJoinInterceptor() {}

    /**
     * Set by the mixin's {@code proceed} runnable right before re-invoking
     * {@code startConnecting}, so the mixin knows not to intercept that second
     * call. Cleared on use. NOT set by {@code ModReviewScreen} any more — on a
     * successful install the game restarts rather than reconnecting.
     */
    private static volatile boolean bypassNextCheck = false;

    /** Called by the mixin's proceed runnable immediately before re-issuing startConnecting. */
    public static void bypassNextCheck() {
        bypassNextCheck = true;
    }

    /** @return true (and clears the flag) if this join should skip the manifest check. */
    public static boolean shouldBypass() {
        if (bypassNextCheck) {
            bypassNextCheck = false;
            return true;
        }
        return false;
    }

    /**
     * Run the full pre-join check on the render thread's behalf. This schedules
     * the HTTP fetch off-thread and, when results are in, marshals back to the
     * client thread to either proceed or open the install screen.
     *
     * @param address   the server address being joined
     * @param serverData the server data (may be null for direct-connect edge cases)
     * @param proceed   callback that performs the real vanilla connect when mods are OK
     */
    public static void intercept(Screen parent, ServerAddress address, ServerData serverData,
                                 Runnable proceed) {
        Minecraft client = Minecraft.getInstance();
        String host = address.getHost();
        int gamePort = address.getPort();

        LOGGER.info("════════ CONDUIT JOIN INTERCEPT ════════");
        LOGGER.info("Stage 1/3 — intercept join to {}:{} (serverData={})",
                host, gamePort, serverData == null ? "null" : "present");
        LOGGER.info("Stage 2/3 — fetching manifest over HTTP...");

        ManifestHttpClient.fetch(host, gamePort).whenComplete((manifestOpt, error) -> {
            if (error != null) {
                // Unexpected runtime exception from the HTTP layer — don't block
                // the join; let vanilla handle it.
                LOGGER.warn("Manifest fetch threw unexpectedly: {} — proceeding with join",
                        error.getMessage());
                client.execute(proceed);
                return;
            }
            if (manifestOpt.isEmpty()) {
                // No Conduit on the server (or endpoint unreachable). Treat as
                // a vanilla server and connect normally.
                LOGGER.info("Stage 3/3 — NO MANIFEST at {}:{} → treating as vanilla, joining normally",
                        host, gamePort);
                LOGGER.info("  (if this server actually runs Conduit, the HTTP endpoint on port {}"
                        + " or {} is unreachable — check the SERVER log for 'Conduit HTTP')", gamePort + 1, gamePort);
                client.execute(proceed);
                return;
            }

            ManifestPayload payload = manifestOpt.get();
            LOGGER.info("Stage 3/3 — manifest received, diffing against installed mods...");
            ManifestVerifier verifier = new ManifestVerifier(new InstalledModIndex());
            ManifestVerifier.DiffResult diff = verifier.diff(payload);

            LOGGER.info("Diff result: {} satisfied, {} missing, {} outdated",
                    diff.satisfied().size(), diff.missing().size(), diff.outdated().size());

            if (!diff.hasWork()) {
                LOGGER.info("All required mods already installed — proceeding with join to {}", host);
                client.execute(proceed);
                return;
            }

            LOGGER.info("Mods needed ({} missing, {} outdated) — opening install screen",
                    diff.missing().size(), diff.outdated().size());
            for (ModEntry m : diff.allNeeded()) {
                LOGGER.info("  → need: {} {} (required={})", m.modId(), m.requiredVersion(), m.required());
            }
            client.execute(() -> {
                // Show the install screen. It owns the rest of the flow: on
                // success it restarts the game to load the new mods; on failure
                // the player stays on the screen and can go back.
                LOGGER.info("Opening ModReviewScreen on the render thread");
                client.setScreen(new ModReviewScreen(payload, diff, parent));
            });
        });
    }
}
