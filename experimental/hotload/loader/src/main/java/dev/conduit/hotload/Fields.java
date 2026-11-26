package dev.conduit.hotload;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Reads and writes private fields of loader classes (FML, ModLauncher) that
 * live in modules which are not opened to mods. {@code setAccessible} is
 * refused across those module boundaries, so this goes through {@link Unsafe}.
 * Spike code: every use is listed in the feasibility report.
 */
public final class Fields {
    private static final Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Fields() {}

    static Field find(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new IllegalStateException("No field " + name + " on " + type.getName());
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object owner, String name) {
        Field f = find(owner.getClass(), name);
        return (T) UNSAFE.getObject(owner, UNSAFE.objectFieldOffset(f));
    }

    public static boolean getBoolean(Object owner, String name) {
        Field f = find(owner.getClass(), name);
        return UNSAFE.getBoolean(owner, UNSAFE.objectFieldOffset(f));
    }

    public static void set(Object owner, String name, Object value) {
        Field f = find(owner.getClass(), name);
        UNSAFE.putObject(owner, UNSAFE.objectFieldOffset(f), value);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getStatic(Class<?> type, String name) {
        Field f = find(type, name);
        if (!Modifier.isStatic(f.getModifiers())) throw new IllegalArgumentException(name + " is not static");
        return (T) UNSAFE.getObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f));
    }

    public static boolean getStaticBoolean(Class<?> type, String name) {
        Field f = find(type, name);
        return UNSAFE.getBoolean(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f));
    }

    public static void setStaticBoolean(Class<?> type, String name, boolean value) {
        Field f = find(type, name);
        UNSAFE.putBoolean(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), value);
    }

    public static void setStatic(Class<?> type, String name, Object value) {
        Field f = find(type, name);
        UNSAFE.putObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), value);
    }

    /** Creates an instance without running a constructor (for package-private event constructors). */
    @SuppressWarnings("unchecked")
    public static <T> T allocate(Class<T> type) {
        try {
            return (T) UNSAFE.allocateInstance(type);
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }
}
