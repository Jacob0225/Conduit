package dev.conduit.hotload;

import net.neoforged.fml.loading.moddiscovery.ModFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Conduit's entry point for loading freshly downloaded mods into the running game.
 * Call on the render thread, before joining a server.
 */
public final class LiveLoad {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/LiveLoad");

    private LiveLoad() {}

    private static volatile Boolean available;
    private static volatile boolean checking;

    /**
     * True if this game can load mods live (a Java that can attach the agent was found). Never
     * blocks: the first call starts the search in the background and returns false until it is done.
     */
    public static boolean available() {
        if (available != null) return available;
        prepare();
        return false;
    }

    /** Starts looking for an attach-capable Java (call early, e.g. at client start). */
    public static synchronized void prepare() {
        if (available != null || checking) return;
        checking = true;
        Thread t = new Thread(() -> {
            boolean ok;
            try {
                ok = dev.conduit.hotload.jvm.JvmAccess.canAttach();
            } catch (Throwable e) {
                ok = false;
            }
            available = ok;
            LOGGER.info(ok ? "Live loading available" : "Live loading unavailable: no Java with jdk.attach found");
        }, "Conduit live-load check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Loads the jars (and anything they bundle), in dependency order, then reloads resources.
     * Completes with the ids of the mods that were loaded. Throws if any jar can't be loaded; the
     * game then has some of the mods and should be restarted.
     */
    public static CompletableFuture<List<String>> load(List<Path> jars) throws Throwable {
        long start = System.nanoTime();
        List<Path> pending = new ArrayList<>(jars);
        List<ModFile> files = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Throwable lastMissing = null;
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Path jar : List.copyOf(pending)) {
                try {
                    for (HotLoader.Loaded loaded : HotLoader.loadAll(jar)) {
                        files.add(loaded.modFile());
                        ids.add(loaded.info().getModId());
                    }
                    pending.remove(jar);
                    progress = true;
                } catch (HotLoader.MissingDependencyException missing) {
                    lastMissing = missing; // its dependency may be later in the list: try again next pass
                }
            }
        }
        if (!pending.isEmpty()) throw lastMissing;
        LOGGER.info("Loaded {} live in {} ms; reloading resources", ids, (System.nanoTime() - start) / 1_000_000);
        return HotResources.addAndReload(files).thenApply(v -> ids);
    }
}
