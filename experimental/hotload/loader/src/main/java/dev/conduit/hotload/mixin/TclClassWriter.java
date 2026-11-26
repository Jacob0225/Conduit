package dev.conduit.hotload.mixin;

import org.objectweb.asm.ClassWriter;

/** ClassWriter whose frame computation resolves types through the game's class loader. */
final class TclClassWriter extends ClassWriter {
    private final ClassLoader loader;

    TclClassWriter(int flags, ClassLoader loader) {
        super(flags);
        this.loader = loader;
    }

    @Override
    protected String getCommonSuperClass(String a, String b) {
        Class<?> ca = load(a), cb = load(b);
        if (ca == null || cb == null) return "java/lang/Object";
        if (ca.isAssignableFrom(cb)) return a;
        if (cb.isAssignableFrom(ca)) return b;
        if (ca.isInterface() || cb.isInterface()) return "java/lang/Object";
        do {
            ca = ca.getSuperclass();
        } while (ca != null && !ca.isAssignableFrom(cb));
        return ca == null ? "java/lang/Object" : ca.getName().replace('.', '/');
    }

    @Override
    protected ClassLoader getClassLoader() {
        return loader;
    }

    private Class<?> load(String internal) {
        try {
            return Class.forName(internal.replace('/', '.'), false, loader);
        } catch (Throwable t) {
            return null;
        }
    }
}
