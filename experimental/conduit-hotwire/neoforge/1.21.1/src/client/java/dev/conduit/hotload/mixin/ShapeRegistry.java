package dev.conduit.hotload.mixin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What mixins wanted to add to already-loaded classes, and where it went instead.
 * Filled by {@link MixinSplitter} before any body is rewritten, then read by
 * {@link CallSiteRewriter} for every patched or newly loaded class.
 */
final class ShapeRegistry {

    /** A method moved into a companion. {@code key} is its {@link dev.conduit.hotload.runtime.HotRuntime} binding. */
    record MovedMethod(String key, boolean isStatic) {}

    /** A field kept in {@link dev.conduit.hotload.runtime.HotRuntime}'s side store. */
    record MovedField(int slot, boolean isStatic, String desc) {}

    /** owner internal name → (name + desc) → moved method. */
    final Map<String, Map<String, MovedMethod>> methods = new HashMap<>();
    /** owner internal name → (name + ":" + desc) → moved field. */
    final Map<String, Map<String, MovedField>> fields = new HashMap<>();
    /** Interfaces that some loaded class should implement but can't. */
    final Set<String> interfaces = new HashSet<>();
    /** Loaded classes that were split, by internal name (subclass lookups start here). */
    final Map<String, Class<?>> splitClasses = new HashMap<>();

    static String methodKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    /**
     * @param superOf internal name to its superclass's internal name (or null). Must not load
     *                classes: it is called while classes are being defined.
     */
    MovedMethod method(String owner, String name, String desc, java.util.function.Function<String, String> superOf) {
        if (methods.isEmpty()) return null;
        String sig = name + desc;
        // Also finds it when called through a subclass (e.g. LocalPlayer for a method added to Entity).
        for (String c = owner; c != null && !c.startsWith("java/"); c = superOf.apply(c)) {
            Map<String, MovedMethod> own = methods.get(c);
            if (own != null && own.containsKey(sig)) return own.get(sig);
        }
        return null;
    }

    MovedField field(String owner, String name, String desc, java.util.function.Function<String, String> superOf) {
        if (fields.isEmpty()) return null;
        String sig = name + ":" + desc;
        for (String c = owner; c != null && !c.startsWith("java/"); c = superOf.apply(c)) {
            Map<String, MovedField> own = fields.get(c);
            if (own != null && own.containsKey(sig)) return own.get(sig);
        }
        return null;
    }

    boolean isEmpty() {
        return methods.isEmpty() && fields.isEmpty() && interfaces.isEmpty();
    }
}
