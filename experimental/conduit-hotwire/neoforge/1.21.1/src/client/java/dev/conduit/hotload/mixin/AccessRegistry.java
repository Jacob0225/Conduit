package dev.conduit.hotload.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What hot-loaded mods' access transformers widen. The AT engine applies them to classes
 * that load later; for classes that were already loaded, {@link CallSiteRewriter} reroutes
 * the mod's accesses through privileged method handles instead.
 *
 * <p>Keys: owner internal name → member keys: a field name, a method name + descriptor,
 * or {@code ""} for the class itself (class access widened).
 */
public final class AccessRegistry {
    public static final String CLASS = "";
    private static final Map<String, Set<String>> WIDENED = new ConcurrentHashMap<>();
    /** {owner, member} for entries that also drop {@code final} ({@code -f}). */
    private static final java.util.List<String[]> FINAL_REMOVALS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AccessRegistry() {}

    /** Parses one {@code accesstransformer.cfg}. */
    public static void load(Path at) throws IOException {
        for (String raw : Files.readAllLines(at)) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            String owner = parts[1].replace('.', '/');
            String member = parts.length >= 3 ? parts[2] : CLASS;
            // Wildcards ("*" / "*()") widen every field or method.
            WIDENED.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(member);
            if (parts[0].endsWith("-f")) FINAL_REMOVALS.add(new String[]{owner, member});
        }
    }

    static boolean hasOwner(String owner) {
        return WIDENED.containsKey(owner);
    }

    static boolean classWidened(String owner) {
        Set<String> s = WIDENED.get(owner);
        return s != null && s.contains(CLASS);
    }

    static boolean fieldWidened(String owner, String name) {
        Set<String> s = WIDENED.get(owner);
        return s != null && (s.contains(name) || s.contains("*") || s.contains(CLASS));
    }

    static boolean methodWidened(String owner, String name, String desc) {
        Set<String> s = WIDENED.get(owner);
        return s != null && (s.contains(name + desc) || s.contains("*()") || s.contains(CLASS));
    }

    static java.util.List<String[]> finalRemovals() {
        return FINAL_REMOVALS;
    }

    static boolean isEmpty() {
        return WIDENED.isEmpty();
    }
}
