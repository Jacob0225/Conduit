package com.jacob0225.conduit.client;

import com.jacob0225.conduit.client.config.ClientConfig;
import com.jacob0225.conduit.client.download.ServerInstallRecord;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ClientModInitializer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side entrypoint for Conduit.
 *
 * <p>In HTTP mode there is almost nothing to do at init time: the join
 * interception is handled entirely by {@code MixinConnectScreen} (which Mixin
 * registers automatically), and the manifest is fetched on demand by
 * {@link com.jacob0225.conduit.client.http.ManifestHttpClient}. No packet
 * types or receivers are registered — the manifest never travels over the
 * Minecraft protocol.
 *
 * <p>The one piece of init-time work is a <b>sanity check</b> on the per-server
 * install records: if a jar a record claims is installed has vanished from both
 * {@code mods/} and the stash (the player deleted it, an external tool cleaned
 * up, etc.), that record can't be trusted to satisfy a join — invalidate it so
 * the next join to that server re-prompts and re-downloads instead of falsely
 * passing the player through to a registry mismatch.
 */
public class ConduitClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Client");

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        verifyInstallRecords();
        LOGGER.info("Conduit client ready — join interception active (HTTP manifest mode)");
    }

    /**
     * For each per-server install record, confirm every jar it claims to own is
     * still findable — either in {@code mods/} (active) or in the stash (swapped
     * out for another server). Any record missing jars is deleted so the next
     * join to that server re-downloads instead of passing through unsatisfied.
     */
    private void verifyInstallRecords() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path modsDir = gameDir.resolve("mods");
        Path stashDir = gameDir.resolve("conduit-stash");

        int checked = 0;
        int invalidated = 0;
        for (ServerInstallRecord rec : ServerInstallRecord.allRecords()) {
            checked++;
            boolean stale = false;
            for (String fn : rec.ownedJarFilenames()) {
                Path inMods  = modsDir.resolve(fn);
                Path inStash = stashDir.resolve(rec.key().stem()).resolve(fn);
                if (!Files.exists(inMods) && !Files.exists(inStash)) {
                    LOGGER.warn("Record for '{}' references jar '{}' that is missing "
                            + "from both mods/ and the stash — invalidating record.",
                            rec.serverName(), fn);
                    stale = true;
                    break;
                }
            }
            if (stale) {
                rec.delete();
                invalidated++;
            }
        }
        if (checked > 0) {
            LOGGER.info("Install-record sanity check: {} record(s) checked, {} invalidated.",
                    checked, invalidated);
        }
    }
}
