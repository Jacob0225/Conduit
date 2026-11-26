package dev.conduit.hotload.runtime;

import com.google.common.collect.MapMaker;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime support for classes patched by the mixin splitter. Patched bytecode
 * (in Minecraft classes, companions and hot-loaded mod classes) calls into here,
 * so this class's name and method signatures are part of the bytecode contract.
 *
 * <ul>
 *   <li>{@link #invoke}: {@code invokedynamic} bootstrap for methods a mixin added to an
 *       already-loaded class (they live in a hidden companion now).</li>
 *   <li>{@link #ifaceCall} / {@link #instanceOf}: interface calls and checks for interfaces
 *       a mixin wanted to add to a loaded class (the class can't gain interfaces).</li>
 *   <li>{@link #getField} and friends: storage for fields a mixin added to a loaded class.</li>
 *   <li>{@link #metafactory}: lambdas whose body moved into a companion.</li>
 * </ul>
 */
public final class HotRuntime {

    private HotRuntime() {}

    // ── Added methods ────────────────────────────────────────────────────────

    private static final Map<String, MethodHandle> METHODS = new ConcurrentHashMap<>();

    public static void bindMethod(String key, MethodHandle handle) {
        METHODS.put(key, handle);
    }

    private static MethodHandle method(String key) {
        MethodHandle handle = METHODS.get(key);
        if (handle == null) throw new IllegalStateException("Conduit hot-load: no companion method bound for " + key);
        return handle;
    }

    public static CallSite invoke(MethodHandles.Lookup caller, String name, MethodType type, String key) {
        return new ConstantCallSite(method(key).asType(type));
    }

    /**
     * Lambdas whose body moved into a companion. {@code LambdaMetafactory} can't be used: in
     * JDK 21 the lambda class it spins names the implementation's class, and a hidden
     * companion can't be found by name. Instead each lambda is a method-handle proxy over
     * the companion method with the captured values bound in.
     */
    public static CallSite metafactory(MethodHandles.Lookup caller, String name, MethodType factoryType,
                                       MethodType samType, String implKey, MethodType instantiatedType) {
        Class<?> iface = factoryType.returnType();
        MethodHandle impl = method(implKey);
        if (factoryType.parameterCount() == 0) {
            Object instance = MethodHandleProxies.asInterfaceInstance(iface, impl);
            return new ConstantCallSite(MethodHandles.constant(iface, instance).asType(factoryType));
        }
        MethodHandle make = MAKE_LAMBDA.bindTo(iface).bindTo(impl)
                .asCollector(Object[].class, factoryType.parameterCount())
                .asType(factoryType);
        return new ConstantCallSite(make);
    }

    private static Object makeLambda(Class<?> iface, MethodHandle impl, Object[] captured) {
        return MethodHandleProxies.asInterfaceInstance(iface, MethodHandles.insertArguments(impl, 0, captured));
    }

    // ── Interfaces that could not be added ──────────────────────────────────

    /** A loaded class that should implement an interface, and the companion keys for its methods. */
    public record Implementer(Class<?> type, Map<String, String> methodKeys) {}

    private static final Map<String, List<Implementer>> IMPLEMENTERS = new ConcurrentHashMap<>();

    public static void addImplementer(String iface, Class<?> type, Map<String, String> methodKeys) {
        IMPLEMENTERS.computeIfAbsent(iface, k -> new CopyOnWriteArrayList<>()).add(new Implementer(type, Map.copyOf(methodKeys)));
    }

    private static Implementer implementerFor(String iface, Class<?> receiver) {
        List<Implementer> list = IMPLEMENTERS.get(iface);
        if (list == null) return null;
        for (Class<?> c = receiver; c != null; c = c.getSuperclass()) {
            for (Implementer impl : list) if (impl.type() == c) return impl;
        }
        return null;
    }

    private static Class<?> ifaceClass(MethodHandles.Lookup caller, String iface) {
        try {
            return Class.forName(iface.replace('/', '.'), false, caller.lookupClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(iface);
        }
    }

    private static final class IfaceSite {
        final MethodHandles.Lookup caller;
        final String iface, name, desc;
        final MethodType type;
        final Map<Class<?>, MethodHandle> cache = new ConcurrentHashMap<>();
        Class<?> ifaceType;

        IfaceSite(MethodHandles.Lookup caller, String iface, String name, String desc, MethodType type) {
            this.caller = caller; this.iface = iface; this.name = name; this.desc = desc; this.type = type;
        }

        MethodHandle resolve(Object receiver) throws ReflectiveOperationException {
            if (receiver == null) throw new NullPointerException("Cannot invoke " + iface + "." + name + " on null");
            MethodHandle cached = cache.get(receiver.getClass());
            if (cached != null) return cached;
            if (ifaceType == null) ifaceType = ifaceClass(caller, iface);
            MethodHandle handle;
            if (ifaceType.isInstance(receiver)) {
                // Real implementation (e.g. a class that loaded after the hot-load and got the mixin normally).
                MethodType mt = MethodType.fromMethodDescriptorString(desc, ifaceType.getClassLoader());
                handle = caller.findVirtual(ifaceType, name, mt);
            } else {
                Implementer impl = implementerFor(iface, receiver.getClass());
                String key = impl == null ? null : impl.methodKeys().get(name + desc);
                if (key == null) {
                    throw new IncompatibleClassChangeError(receiver.getClass().getName() + " does not implement "
                            + iface + "." + name + desc + " (Conduit hot-load)");
                }
                handle = method(key);
            }
            handle = handle.asType(type);
            cache.put(receiver.getClass(), handle);
            return handle;
        }
    }

    private static final MethodHandle RESOLVE;
    private static final MethodHandle IS_INSTANCE;
    private static final MethodHandle MAKE_LAMBDA;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            RESOLVE = lookup.findVirtual(IfaceSite.class, "resolve", MethodType.methodType(MethodHandle.class, Object.class));
            IS_INSTANCE = lookup.findStatic(HotRuntime.class, "isInstance",
                    MethodType.methodType(boolean.class, String.class, Class.class, Object.class));
            MAKE_LAMBDA = lookup.findStatic(HotRuntime.class, "makeLambda",
                    MethodType.methodType(Object.class, Class.class, MethodHandle.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** {@code invokedynamic name (Object receiver, args...) [iface, desc]}: an interface call. */
    public static CallSite ifaceCall(MethodHandles.Lookup caller, String name, MethodType type, String iface, String desc) {
        IfaceSite site = new IfaceSite(caller, iface, name, desc, type);
        MethodHandle resolver = RESOLVE.bindTo(site);                       // (Object)MethodHandle
        MethodHandle invoker = MethodHandles.exactInvoker(type);           // (MethodHandle, Object, args...)
        return new ConstantCallSite(MethodHandles.foldArguments(invoker, resolver));
    }

    /** {@code invokedynamic instanceOf (Object)Z [iface]}. */
    public static CallSite instanceOf(MethodHandles.Lookup caller, String name, MethodType type, String iface) {
        MethodHandle test = MethodHandles.insertArguments(IS_INSTANCE, 0, iface, ifaceClass(caller, iface));
        return new ConstantCallSite(test.asType(type));
    }

    private static boolean isInstance(String iface, Class<?> type, Object value) {
        return value != null && (type.isInstance(value) || implementerFor(iface, value.getClass()) != null);
    }

    // ── Access transformers on already-loaded classes ───────────────────────

    private static final MethodHandle CLASS_CAST, CLASS_IS_INSTANCE;

    static {
        try {
            CLASS_CAST = MethodHandles.lookup().findVirtual(Class.class, "cast", MethodType.methodType(Object.class, Object.class));
            CLASS_IS_INSTANCE = MethodHandles.lookup().findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * {@code invokedynamic name(type) [kind, declaringClass, memberDesc]}: an access that a
     * hot-loaded mod's access transformer widened, on a class that was loaded before the AT
     * could apply. Resolved with the declaring class's private lookup. Kinds match
     * {@code CallSiteRewriter} (1 getfield … 11 new array).
     */
    public static CallSite access(MethodHandles.Lookup caller, String name, MethodType type, int kind,
                                  String owner, String desc) throws Throwable {
        ClassLoader loader = caller.lookupClass().getClassLoader();
        Class<?> decl = Class.forName(owner.replace('/', '.'), false, loader);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(decl, dev.conduit.hotload.HotLoader.implLookup());
        MethodHandle handle = switch (kind) {
            case 1 -> lookup.findGetter(decl, name, fieldType(desc, loader));
            case 2 -> finalAwareSetter(lookup, decl, name, fieldType(desc, loader), false);
            case 3 -> lookup.findStaticGetter(decl, name, fieldType(desc, loader));
            case 4 -> finalAwareSetter(lookup, decl, name, fieldType(desc, loader), true);
            case 5 -> lookup.findVirtual(decl, name, MethodType.fromMethodDescriptorString(desc, loader));
            case 6 -> lookup.findStatic(decl, name, MethodType.fromMethodDescriptorString(desc, loader));
            case 7 -> lookup.findConstructor(decl, MethodType.fromMethodDescriptorString(desc, loader));
            case 8 -> CLASS_CAST.bindTo(decl);
            case 9 -> CLASS_IS_INSTANCE.bindTo(decl);
            case 10 -> MethodHandles.constant(Class.class, decl);
            case 11 -> MethodHandles.arrayConstructor(decl.arrayType());
            default -> throw new IllegalArgumentException("access kind " + kind);
        };
        return new ConstantCallSite(handle.asType(type));
    }

    private static final MethodHandle STATIC_FINAL_SETTER;

    static {
        try {
            STATIC_FINAL_SETTER = MethodHandles.lookup().findStatic(HotRuntime.class, "setStaticFinal",
                    MethodType.methodType(void.class, Class.class, String.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void setStaticFinal(Class<?> owner, String name, Object value) {
        dev.conduit.hotload.Fields.setStatic(owner, name, value);
    }

    /**
     * {@code invokedynamic name(Host, args)ret [owner, desc, host]}: {@code super.name(...)} from code
     * that moved out of {@code host} into a companion. Runs {@code owner.name} non-virtually, as the
     * host's own {@code invokespecial} would.
     */
    public static CallSite special(MethodHandles.Lookup caller, String name, MethodType type,
                                   String owner, String desc, String host) throws Throwable {
        ClassLoader loader = caller.lookupClass().getClassLoader();
        Class<?> ownerType = Class.forName(owner.replace('/', '.'), false, loader);
        Class<?> hostType = Class.forName(host.replace('/', '.'), false, loader);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(hostType, dev.conduit.hotload.HotLoader.implLookup());
        MethodHandle handle = lookup.findSpecial(ownerType, name, MethodType.fromMethodDescriptorString(desc, loader), hostType);
        return new ConstantCallSite(handle.asType(type));
    }

    private static Class<?> fieldType(String desc, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString("()" + desc, loader).returnType();
    }

    /** ATs often drop {@code final} too ({@code public-f}); method handle setters refuse final fields. */
    private static MethodHandle finalAwareSetter(MethodHandles.Lookup lookup, Class<?> decl, String name, Class<?> type,
                                                 boolean isStatic) throws ReflectiveOperationException {
        try {
            return isStatic ? lookup.findStaticSetter(decl, name, type) : lookup.findSetter(decl, name, type);
        } catch (IllegalAccessException finalField) {
            java.lang.reflect.Field field = decl.getDeclaredField(name);
            if (isStatic) {
                if (type.isPrimitive()) throw new UnsupportedOperationException("writing primitive static final " + decl.getName() + "." + name);
                // Reflection refuses static finals; write through Unsafe.
                return MethodHandles.insertArguments(STATIC_FINAL_SETTER, 0, decl, name).asType(MethodType.methodType(void.class, type));
            }
            field.setAccessible(true);
            return MethodHandles.lookup().unreflectSetter(field);
        }
    }

    // ── Overrides of methods that were final when their class loaded ────────

    private static final Map<String, java.util.Set<String>> FINAL_OVERRIDERS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Class<?>, Object>> FINAL_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<boolean[]> BYPASS = ThreadLocal.withInitial(() -> new boolean[1]);
    private static final Object NO_OVERRIDE = new Object();

    public static void addFinalOverrider(String key, String internalName) {
        FINAL_OVERRIDERS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(internalName);
        FINAL_CACHE.remove(key);
    }

    /** shimmed method key -> (class internal name -> companion method key) */
    private static final Map<String, Map<String, String>> COMPANION_OVERRIDES = new ConcurrentHashMap<>();

    /** A mixin added an override of {@code key} to {@code internalName}; its body is companion method {@code methodKey}. */
    public static void addCompanionOverride(String key, String internalName, String methodKey) {
        COMPANION_OVERRIDES.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(internalName, methodKey);
        FINAL_CACHE.remove(key);
    }

    public static boolean isFinalOverrider(String key, String internalName) {
        java.util.Set<String> set = FINAL_OVERRIDERS.get(key);
        return set != null && set.contains(internalName);
    }

    /** Called right before a {@code super.m()} that must run the original body, not dispatch again. */
    public static void bypassNextFinal() {
        BYPASS.get()[0] = true;
    }

    /**
     * Called from the prologue of a shimmed final method. Returns the handle of the receiver's
     * override ({@code conduit$final$<name>}), typed {@code (Owner, args)ret}, or null to run
     * the original body.
     */
    public static MethodHandle finalOverride(Object self, String key) throws Throwable {
        boolean[] bypass = BYPASS.get();
        if (bypass[0]) {
            bypass[0] = false;
            return null;
        }
        java.util.Set<String> overriders = FINAL_OVERRIDERS.getOrDefault(key, java.util.Set.of());
        if (overriders.isEmpty() && !COMPANION_OVERRIDES.containsKey(key)) return null;
        Map<Class<?>, Object> cache = FINAL_CACHE.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Object cached = cache.get(self.getClass());
        if (cached == null) {
            cached = resolveFinalOverride(self.getClass(), key, overriders);
            cache.put(self.getClass(), cached);
        }
        return cached == NO_OVERRIDE ? null : (MethodHandle) cached;
    }

    private static Object resolveFinalOverride(Class<?> receiver, String key, java.util.Set<String> overriders) throws Throwable {
        int dot = key.lastIndexOf('.', key.indexOf('('));
        String owner = key.substring(0, dot);
        String name = key.substring(dot + 1, key.indexOf('('));
        String desc = key.substring(key.indexOf('('));
        Class<?> ownerType = null;
        for (Class<?> c = receiver; c != null; c = c.getSuperclass()) {
            if (c.getName().replace('.', '/').equals(owner)) { ownerType = c; break; }
        }
        Map<String, String> companions = COMPANION_OVERRIDES.getOrDefault(key, Map.of());
        for (Class<?> c = receiver; c != null && c != ownerType; c = c.getSuperclass()) {
            String internal = c.getName().replace('.', '/');
            String companionKey = companions.get(internal);
            if (companionKey != null) {
                MethodType mt = MethodType.fromMethodDescriptorString(desc, c.getClassLoader());
                return method(companionKey).asType(mt.insertParameterTypes(0, ownerType));
            }
            if (overriders.contains(internal)) {
                MethodType mt = MethodType.fromMethodDescriptorString(desc, c.getClassLoader());
                MethodHandle h = MethodHandles.privateLookupIn(c, dev.conduit.hotload.HotLoader.implLookup())
                        .findVirtual(c, "conduit$final$" + name, mt);
                return h.asType(mt.insertParameterTypes(0, ownerType));
            }
        }
        return NO_OVERRIDE;
    }

    // ── Fields that could not be added ──────────────────────────────────────

    private record Slot(String desc, Object defaultValue) {}

    private static final List<Slot> SLOTS = new CopyOnWriteArrayList<>();
    private static final ConcurrentMap<Object, Object[]> INSTANCE_VALUES = new MapMaker().weakKeys().makeMap(); // identity, weak
    private static final ConcurrentMap<Integer, Object> STATIC_VALUES = new ConcurrentHashMap<>();
    private static final Object NULL = new Object();

    /** Reserves a storage slot for a field of type {@code desc}; returns its index. */
    public static synchronized int newSlot(String desc) {
        Object def = switch (desc.charAt(0)) {
            case 'Z' -> Boolean.FALSE;
            case 'B' -> (byte) 0;
            case 'C' -> (char) 0;
            case 'S' -> (short) 0;
            case 'I' -> 0;
            case 'J' -> 0L;
            case 'F' -> 0F;
            case 'D' -> 0D;
            default -> null;
        };
        SLOTS.add(new Slot(desc, def));
        return SLOTS.size() - 1;
    }

    private static Object unwrap(Object stored, int slot) {
        if (stored == null) return SLOTS.get(slot).defaultValue();
        return stored == NULL ? null : stored;
    }

    public static Object getField(Object owner, int slot) {
        Object[] values = INSTANCE_VALUES.get(owner);
        return unwrap(values == null || slot >= values.length ? null : values[slot], slot);
    }

    public static void putField(Object owner, Object value, int slot) {
        INSTANCE_VALUES.compute(owner, (k, values) -> {
            if (values == null || slot >= values.length) {
                Object[] grown = new Object[SLOTS.size()];
                if (values != null) System.arraycopy(values, 0, grown, 0, values.length);
                values = grown;
            }
            values[slot] = value == null ? NULL : value;
            return values;
        });
    }

    public static Object getStatic(int slot) {
        return unwrap(STATIC_VALUES.get(slot), slot);
    }

    public static void putStatic(Object value, int slot) {
        STATIC_VALUES.put(slot, value == null ? NULL : value);
    }
}
