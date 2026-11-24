package com.jacob0225.conduit.client.download;

import com.jacob0225.conduit.client.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the {@code mods/} folder in sync with the server the player is about to
 * join by physically moving jars between {@code mods/} and a per-server stash.
 *
 * <p>Problem this solves: Fabric loads <b>every</b> jar in {@code mods/} at
 * startup, regardless of which server you're joining. So if server A and server
 * B need incompatible mod sets, the folder must physically contain only the
 * right set at restart time — otherwise leftover mods from the other server can
 * crash the client.
 *
 * <p>Strategy (stash &amp; swap):
 * <ol>
 *   <li>Build the set of jar filenames <b>every</b> known server record owns.</li>
 *   <li>Anything in {@code mods/} that's owned by a <i>different</i> server gets
 *       moved out to {@code conduit-stash/<otherServerStem>/}.</li>
 *   <li>Anything the <i>target</i> server owns that's currently stashed gets moved
 *       back into {@code mods/}.</li>
 * </ol>
 *
 * <p>Conduit only ever touches jars that some record claims ownership of. A jar
 * the player installed manually (no record references it) is never moved — the
 * player's own mods are left strictly alone.
 *
 * <p>Stash layout: {@code <gameDir>/conduit-stash/<serverStem>/<jarFile>}, where
 * {@code serverStem} is the record filename without extension (e.g.
 * {@code mc_example_com_25565}).
 */
public class ModStashManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Stash");

    public static final String STASH_DIR = "conduit-stash";
    public static final String MODS_DIR  = "mods";

    private final Path gameDir;

    public ModStashManager() {
        this(PlatformPaths.gameDir());
    }

    /** Testable constructor. */
    public ModStashManager(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** Outcome of a reconcile: how many jars moved each way, and whether mods/ changed. */
    public record ReconcileResult(int movedOut, int movedIn, boolean changed) {}

    /**
     * Make {@code mods/} contain the target server's mod set.
     *
     * <p>After this returns, every jar the target server owns is in {@code mods/},
     * and every jar owned only by other servers is stashed. Returns whether the
     * contents of {@code mods/} actually changed (so the caller knows a restart
     * is required to load the new set).
     *
     * @param targetKey        the server being joined
     * @param targetFilenames  the jars that should be in mods/ for this server
     */
    public ReconcileResult reconcile(ServerInstallRecord.ServerKey targetKey,
                                     Set<String> targetFilenames) {
        int movedOut = 0;
        int movedIn  = 0;

        // Map every owned jar filename → the server that owns it. If two servers
        // somehow reference the same filename, the first one wins; that's fine,
        // it just means the jar legitimately satisfies both.
        Map<String, String> ownerByFilename = new HashMap<>();
        for (ServerInstallRecord rec : ServerInstallRecord.allRecords()) {
            String owner = rec.key().stem();
            for (String fn : rec.managedJarFilenames()) {
                ownerByFilename.putIfAbsent(fn, owner);
            }
        }

        Path modsDir = gameDir.resolve(MODS_DIR);
        if (!Files.isDirectory(modsDir)) {
            return new ReconcileResult(0, 0, false);
        }

        // ── Move other servers' jars OUT of mods/ ───────────────────────────
        try {
            try (var stream = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path jar : stream) {
                    String fn = jar.getFileName().toString();
                    String owner = ownerByFilename.get(fn);
                    if (owner == null) continue;             // not a Conduit-owned jar
                    if (targetFilenames.contains(fn)) continue;  // target needs it too

                    // This Conduit-managed jar is absent from the target set.
                    // It may be a superseded version previously owned by this
                    // same server, so target ownership alone is not enough to
                    // keep it active.
                    Path stashSlot = gameDir.resolve(STASH_DIR).resolve(owner).resolve(fn);
                    try {
                        Files.createDirectories(stashSlot.getParent());
                        Files.move(jar, stashSlot, StandardCopyOption.REPLACE_EXISTING);
                        movedOut++;
                        LOGGER.info("Stashed '{}' (owned by '{}') out of mods/", fn, owner);
                    } catch (IOException e) {
                        LOGGER.error("Could not stash '{}' out of mods/: {}", fn, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not scan mods/ for stashing: {}", e.getMessage());
        }

        // ── Move the target's jars IN from the stash ───────────────────────
        // A jar shared by several servers is stashed under whichever record
        // claimed it first, so search every server's stash folder, not just
        // the target's own (otherwise a shared jar is never restored).
        Path stashRoot = gameDir.resolve(STASH_DIR);
        if (Files.isDirectory(stashRoot)) {
            try (var owners = Files.newDirectoryStream(stashRoot, Files::isDirectory)) {
                for (Path ownerStash : owners) {
                    try (var stream = Files.newDirectoryStream(ownerStash, "*.jar")) {
                        for (Path stashed : stream) {
                            String fn = stashed.getFileName().toString();
                            // Only restore jars the target actually needs (the stash may
                            // contain an older version; the record is authoritative).
                            if (!targetFilenames.contains(fn)) continue;
                            Path dest = modsDir.resolve(fn);
                            if (Files.exists(dest)) {
                                // Already present in mods/ — drop the stale stash copy.
                                try { Files.delete(stashed); } catch (IOException ignored) {}
                                continue;
                            }
                            try {
                                Files.move(stashed, dest, StandardCopyOption.REPLACE_EXISTING);
                                movedIn++;
                                LOGGER.info("Restored '{}' from stash '{}' into mods/",
                                        fn, ownerStash.getFileName());
                            } catch (IOException e) {
                                LOGGER.error("Could not restore '{}' from stash: {}", fn, e.getMessage());
                            }
                        }
                    } catch (IOException e) {
                        LOGGER.error("Could not scan stash '{}': {}", ownerStash.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Could not scan the stash for '{}': {}", targetKey.stem(), e.getMessage());
            }
        }

        boolean changed = movedOut > 0 || movedIn > 0;
        if (changed) {
            LOGGER.info("Reconcile for '{}': {} moved out, {} moved in — restart required to load new set",
                    targetKey.stem(), movedOut, movedIn);
        } else {
            LOGGER.info("Reconcile for '{}': no changes — mods/ already correct", targetKey.stem());
        }
        return new ReconcileResult(movedOut, movedIn, changed);
    }

    /**
     * Returns the set of Conduit-owned jars that are currently sitting in
     * {@code mods/}. Used by the startup sanity check.
     */
    public Set<String> ownedJarsInModsDir() {
        Set<String> present = new HashSet<>();
        Path modsDir = gameDir.resolve(MODS_DIR);
        if (!Files.isDirectory(modsDir)) return present;

        Set<String> allOwned = new HashSet<>();
        for (ServerInstallRecord rec : ServerInstallRecord.allRecords()) {
            allOwned.addAll(rec.ownedJarFilenames());
        }

        try (var stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : stream) {
                String fn = jar.getFileName().toString();
                if (allOwned.contains(fn)) present.add(fn);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan mods/ for owned jars: {}", e.getMessage());
        }
        return present;
    }

    /** True if {@code filename} is in mods/ or in any server's stash folder. */
    public static boolean isAvailable(Path gameDir, String filename) {
        if (Files.exists(gameDir.resolve(MODS_DIR).resolve(filename))) return true;
        Path stashRoot = gameDir.resolve(STASH_DIR);
        if (!Files.isDirectory(stashRoot)) return false;
        try (var owners = Files.newDirectoryStream(stashRoot, Files::isDirectory)) {
            for (Path ownerStash : owners) {
                if (Files.exists(ownerStash.resolve(filename))) return true;
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan the stash: {}", e.getMessage());
        }
        return false;
    }

    /** All known server keys (one per record file). */
    public static List<ServerInstallRecord.ServerKey> allServerKeys() {
        return ServerInstallRecord.allRecords().stream()
                .map(ServerInstallRecord::key)
                .toList();
    }
}
