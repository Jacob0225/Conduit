package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.download.ModStashManager;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import com.jacob0225.conduit.client.http.ManifestHttpClient;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
import com.jacob0225.conduit.client.ui.ModSwitchScreen;
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
 *     <li>Check the per-server {@link ServerInstallRecord} — does it satisfy the
 *         manifest? If yes → run {@code proceed} (the captured vanilla connect).
 *     <li>If not (first join, mod added/updated, record missing) → show
 *         {@link ModReviewScreen}; on successful install it writes the record
 *         and restarts to load the new mods.
 *   </ol>
 *
 * <p>The record is the single source of truth for "are we good for this server?"
 * We don't diff against live loaded mods: a player can keep separate mod sets
 * for different servers, and the record keeps each server's install isolated.
 *
 * <p><b>Recursion guard.</b> When installation finishes we re-invoke the join
 * by calling {@code ConnectScreen.startConnecting} again — which would normally
 * re-enter the mixin and re-check. To break that cycle, {@link #bypassNextCheck}
 * marks that a check has just passed for a given server, so the immediate
 * re-join skips the HTTP round-trip. The flag is cleared after one bypass.
 */
public final class ConduitJoinInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Join");

    private ConduitJoinInterceptor() {}

    /**
     * Set right after a successful check so the consequent re-join (post-install
     * or the original connect) isn't re-intercepted. Cleared on use.
     */
    private static volatile boolean bypassNextCheck = false;

    /**
     * Mark that the next join should skip the manifest check. Used internally
     * right before re-issuing {@code startConnecting} after a successful install.
     */
    public static void bypassNextCheck() {
        bypassNextCheck = true;
    }

    /** @return true if the mixin should let this join through unchecked. */
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
                LOGGER.info("  (if this server actually runs Conduit, its HTTP endpoint is unreachable"
                        + " — check the SERVER log for 'Conduit HTTP' and confirm port 25566 is open)");
                client.execute(proceed);
                return;
            }

            ManifestPayload payload = manifestOpt.get();
            LOGGER.info("Stage 3/3 — manifest received ({} mods), checking install record...",
                    payload.mods().size());

            ServerInstallRecord record = ServerInstallRecord.forServer(host, gamePort);
            if (record.isSatisfied(payload)) {
                // Record says we're good for this server — but the player may be
                // switching FROM another server whose mod set is still in mods/.
                // Reconcile the stash: move other servers' jars out and this one's
                // back in. If mods/ actually changed, we can't just join (Fabric
                // loaded the old set at startup) — show the switch screen so the
                // player can restart. If nothing moved, join normally.
                ModStashManager stash = new ModStashManager();
                ModStashManager.ReconcileResult rc =
                        stash.reconcile(record.key(), record.ownedJarFilenames());
                if (rc.changed()) {
                    LOGGER.info("Mod set differs from what's loaded — opening switch screen for {}:{}",
                            host, gamePort);
                    client.execute(() ->
                            client.setScreen(new ModSwitchScreen(payload.serverName(), parent)));
                } else {
                    LOGGER.info("Install record satisfies manifest for {}:{} and mods/ already correct — joining",
                            host, gamePort);
                    client.execute(proceed);
                }
                return;
            }

            int required = (int) payload.mods().stream().filter(ModEntry::required).count();
            LOGGER.info("Install record does NOT satisfy manifest — opening install screen "
                    + "({} required mod(s))", required);
            client.execute(() -> {
                LOGGER.info("Opening ModReviewScreen on the render thread");
                client.setScreen(new ModReviewScreen(payload, address, serverData, parent));
            });
        });
    }

    /** Package-private for tests / state reset. */
    static void resetState() {
        bypassNextCheck = false;
    }
}
