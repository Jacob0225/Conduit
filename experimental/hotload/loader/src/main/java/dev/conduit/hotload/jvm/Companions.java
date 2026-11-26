package dev.conduit.hotload.jvm;

import dev.conduit.hotload.HotLoader;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Companion classes hold the code a mixin would have <em>added</em> to an already
 * loaded class. They are hidden classes defined as nestmates of the target, so they
 * can use its private members without the target's class file changing shape.
 * Patched method bodies reach them through {@code invokedynamic} + {@link #bootstrap}.
 */
public final class Companions {

    private static final Map<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    private Companions() {}

    /** Defines {@code bytes} as a hidden nestmate of {@code host} (same package required). */
    public static Class<?> defineNestmate(Class<?> host, byte[] bytes) throws IllegalAccessException {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(host, HotLoader.implLookup());
        return lookup.defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
    }

    public static void bind(String key, MethodHandle handle) {
        HANDLES.put(key, handle);
    }

    /** Bootstrap for patched call sites: {@code invokedynamic name(type) [key]}. */
    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String key) {
        MethodHandle handle = HANDLES.get(key);
        if (handle == null) throw new IllegalStateException("No companion bound for " + key);
        return new ConstantCallSite(handle.asType(type));
    }
}
