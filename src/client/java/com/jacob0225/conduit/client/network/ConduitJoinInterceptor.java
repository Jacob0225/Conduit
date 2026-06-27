package com.jacob0225.conduit.client.network;

import com.jacob0225.conduit.client.download.InstalledModIndex;
import com.jacob0225.conduit.client.download.ManifestVerifier;
import com.jacob0225.conduit.client.http.ManifestHttpClient;
import com.jacob0225.conduit.client.ui.ModReviewScreen;
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
 *     <li>Diff it against installed mods.
 *     <li>If all satisfied → run {@code proceed} (the captured vanilla connect).
 *     <li>If missing/outdated → show {@link ModReviewScreen}; on successful
 *         install it calls {@code proceed} itself to do the real join.
 *   </ol>
 *
 * <p><b>Recursion guard.</b> When installation finishes we re-invoke the join
 * by calling {@code ConnectScreen.startConnecting} again — which would normally
 * re-enter the mixin and re-check. To break that cycle, {@link #checkedRecently}
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

        LOGGER.info("Intercepting join to {}:{} — fetching manifest", host, gamePort);

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
                LOGGER.info("No Conduit manifest at {}:{} — joining as vanilla", host, gamePort);
                client.execute(proceed);
                return;
            }

            ManifestPayload payload = manifestOpt.get();
            ManifestVerifier verifier = new ManifestVerifier(new InstalledModIndex());
            ManifestVerifier.DiffResult diff = verifier.diff(payload);

            if (!diff.hasWork()) {
                LOGGER.info("All required mods already installed — joining {}", host);
                client.execute(proceed);
                return;
            }

            LOGGER.info("Mods needed ({} missing, {} outdated) — opening install screen",
                    diff.missing().size(), diff.outdated().size());
            client.execute(() -> {
                // Show the install screen. It owns the rest of the flow: on
                // success it reconnects; on failure the player stays on the
                // screen and can go back.
                client.setScreen(new ModReviewScreen(payload, diff, address, serverData, parent));
            });
        });
    }

    /** Package-private for tests / state reset. */
    static void resetState() {
        bypassNextCheck = false;
    }
}
