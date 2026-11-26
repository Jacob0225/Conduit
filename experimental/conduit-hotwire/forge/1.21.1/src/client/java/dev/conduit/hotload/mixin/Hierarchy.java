package dev.conduit.hotload.mixin;

import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Superclass lookups that never load a class: loaded classes answer directly, others are
 * read from their class file. Safe to call while a class is being defined.
 */
final class Hierarchy {
    private static final String NONE = "";
    private final ClassLoader loader;
    private final MethodHandle findLoaded;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    Hierarchy(ClassLoader loader) {
        this.loader = loader;
        try {
            this.findLoaded = dev.conduit.hotload.HotLoader.implLookup().findVirtual(ClassLoader.class, "findLoadedClass",
                    MethodType.methodType(Class.class, String.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    ClassLoader loader() {
        return loader;
    }

    /** True if the class is already defined in the game's loader (without loading it). */
    boolean isLoaded(String internal) {
        try {
            return findLoaded.invoke(loader, internal.replace('/', '.')) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    String superOf(String internal) {
        String cached = cache.computeIfAbsent(internal, this::lookup);
        return cached == NONE ? null : cached;
    }

    private String lookup(String internal) {
        try {
            Class<?> c = (Class<?>) findLoaded.invoke(loader, internal.replace('/', '.'));
            if (c != null) return c.getSuperclass() == null ? NONE : c.getSuperclass().getName().replace('.', '/');
            try (InputStream in = loader.getResourceAsStream(internal + ".class")) {
                if (in == null) return NONE;
                String sup = new ClassReader(in.readAllBytes()).getSuperName();
                return sup == null ? NONE : sup;
            }
        } catch (Throwable t) {
            return NONE;
        }
    }
}
