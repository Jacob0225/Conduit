package dev.conduit.hotload.mixin;

import dev.conduit.hotload.Fields;
import dev.conduit.hotload.HotLoader;
import dev.conduit.hotload.jvm.JvmAccess;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.service.MixinService;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Applies a hot-loaded mod's mixin configs to the running game.
 *
 * <ol>
 *   <li>Register the configs with Mixin and make it prepare them now (Mixin only picks up
 *       new configs before the first class is transformed), hiding the already-loaded
 *       targets from its "loaded too early" check.</li>
 *   <li>Targets that are not loaded yet: nothing to do, Mixin applies normally when they load.</li>
 *   <li>Loaded targets: ask ModLauncher to rebuild each class from its original bytes through the
 *       full transformer chain (so it now includes the new mixins), then {@link MixinSplitter}.</li>
 *   <li>Rewrite the mod's own classes as they load ({@link CallSiteRewriter}), so their casts and
 *       calls to accessor or added interfaces work on classes that couldn't gain them.</li>
 * </ol>
 */
public final class HotMixins {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HotMixins");
    private static final Set<Module> HOT_MODULES = new CopyOnWriteArraySet<>();
    private static final Set<String> HOT_PACKAGES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** True if the class belongs to a hot-loaded mod (by package). */
    static boolean isHotClass(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash > 0 && HOT_PACKAGES.contains(internal.substring(0, slash).replace('/', '.'));
    }
    private static boolean transformerInstalled;

    public record Outcome(List<String> configs, List<String> loadedTargets, List<String> laterTargets,
                          MixinSplitter.Result split) {}

    private HotMixins() {}

    public static Outcome apply(ModFile modFile, Module module) throws Throwable {
        HOT_MODULES.add(module);
        HOT_PACKAGES.addAll(module.getPackages());
        Instrumentation inst = JvmAccess.instrumentation();
        installTransformer(inst); // also needed for access transformers and final-method shims
        List<String> configNames = modFile.getMixinConfigs().stream().map(c -> c.config()).toList();
        if (configNames.isEmpty()) return null;

        ClassLoader tcl = HotLoader.class.getClassLoader();

        // 1. Register + prepare the configs.
        Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        Object processor = Fields.get(transformer, "processor");
        List<?> configs = Fields.get(processor, "configs");
        Set<Object> before = new HashSet<>(configs);
        String modId = modFile.getModInfos().get(0).getModId();
        for (String name : configNames) Mixins.addConfiguration(name);
        for (Config config : Mixins.getConfigs()) {
            if (configNames.contains(config.getName())) config.getConfig().decorate(FabricUtil.KEY_MOD_ID, modId);
        }
        Set<String> tracked = Fields.get(MixinService.getService().getClassTracker(), "loadedClasses");
        Set<String> snapshot;
        synchronized (tracked) {
            snapshot = new HashSet<>(tracked);
            tracked.clear(); // let Mixin accept targets that are already loaded
        }
        // Select while holding Mixin's re-entrance lock, as at startup (where selection runs inside
        // the first transform): classes loaded meanwhile (config plugins) must not start another selection.
        Object lock = Fields.get(processor, "lock");
        MethodHandle push = HotLoader.implLookup().findVirtual(lock.getClass(), "push", MethodType.methodType(lock.getClass()));
        MethodHandle pop = HotLoader.implLookup().findVirtual(lock.getClass(), "pop", MethodType.methodType(lock.getClass()));
        push.invoke(lock);
        try {
            MethodHandle select = HotLoader.implLookup().findVirtual(processor.getClass(), "select",
                    MethodType.methodType(void.class, MixinEnvironment.class));
            select.invoke(processor, MixinEnvironment.getCurrentEnvironment());
        } finally {
            pop.invoke(lock);
            synchronized (tracked) { tracked.addAll(snapshot); }
        }
        if ((boolean) Fields.getBoolean(processor, "errorState")) {
            throw new IllegalStateException("Mixin rejected " + configNames + " (see log)");
        }

        // 2. Which targets are already loaded?
        Set<String> targets = new LinkedHashSet<>();
        for (Object c : configs) {
            if (!before.contains(c)) targets.addAll(((IMixinConfig) c).getTargets());
        }
        MethodHandle findLoaded = HotLoader.implLookup().findVirtual(ClassLoader.class, "findLoadedClass",
                MethodType.methodType(Class.class, String.class));
        MethodHandle rebuild = HotLoader.implLookup().findVirtual(Class.forName("cpw.mods.cl.ModuleClassLoader"),
                "getMaybeTransformedClassBytes", MethodType.methodType(byte[].class, String.class, String.class));
        List<MixinSplitter.Target> loadedTargets = new ArrayList<>();
        List<String> loadedNames = new ArrayList<>(), later = new ArrayList<>();
        for (String target : targets) {
            String dotted = target.replace('/', '.');
            Class<?> type = (Class<?>) findLoaded.invoke(tcl, dotted);
            if (type == null) { later.add(dotted); continue; }
            loadedNames.add(dotted);
            ClassNode current = node(JvmAccess.currentBytes(type));
            ClassNode patched = node((byte[]) rebuild.invoke(tcl, dotted, "classloading"));
            loadedTargets.add(new MixinSplitter.Target(type, current, patched));
            for (Module m : List.of(module, HotMixins.class.getModule())) JvmAccess.addReads(type.getModule(), m);
        }

        // 3. Split + redefine.
        MixinSplitter.Result result = loadedTargets.isEmpty() ? null
                : MixinSplitter.split(loadedTargets, tcl, inst);
        LOGGER.info("Hot mixins for {}: {} loaded targets split, {} applied on load", modId, loadedNames.size(), later.size());
        return new Outcome(configNames, loadedNames, later, result);
    }

    /** Cheap check: does any descriptor or type in the class change under the eraser? */
    private static boolean mentionsErased(ClassNode node, org.objectweb.asm.commons.Remapper eraser) {
        java.util.function.Predicate<String> d = desc -> desc != null && !eraser.mapDesc(desc).equals(desc);
        java.util.function.Predicate<String> md = desc -> desc != null && !eraser.mapMethodDesc(desc).equals(desc);
        for (var f : node.fields) if (d.test(f.desc)) return true;
        for (var m : node.methods) {
            if (md.test(m.desc)) return true;
            for (var insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode c && md.test(c.desc)) return true;
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f && d.test(f.desc)) return true;
                if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode i && md.test(i.desc)) return true;
                if (insn instanceof org.objectweb.asm.tree.TypeInsnNode t && !eraser.map(t.desc).equals(t.desc)) return true;
                if (insn instanceof org.objectweb.asm.tree.FrameNode fr) {
                    for (var lists : java.util.Arrays.asList(fr.local, fr.stack)) {
                        if (lists == null) continue;
                        for (Object o : lists) if (o instanceof String s && !eraser.map(s).equals(s)) return true;
                    }
                }
            }
            if (m.localVariables != null) for (var lv : m.localVariables) if (d.test(lv.desc)) return true;
        }
        return false;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Rewrites hot-loaded mods' classes as they are defined (after ModLauncher and Mixin ran). */
    private static synchronized void installTransformer(Instrumentation inst) {
        if (transformerInstalled) return;
        transformerInstalled = true;
        ClassLoader tcl = HotLoader.class.getClassLoader();
        Hierarchy hierarchy = new Hierarchy(tcl);
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (redefined != null || module == null || !HOT_MODULES.contains(module)) return null;
                if (MixinSplitter.REGISTRY.isEmpty() && AccessRegistry.isEmpty()) return null;
                try {
                    ClassNode node = node(bytes);
                    CallSiteRewriter rewriter = new CallSiteRewriter(MixinSplitter.REGISTRY, hierarchy);
                    boolean changed = FinalShim.rewriteClass(node, hierarchy);
                    for (MethodNode m : node.methods) changed |= rewriter.rewrite(m, null, node.name);
                    // Erase classes this mod may not name (loaded before its ATs) to their public supertype.
                    var eraser = rewriter.eraser(node.name);
                    boolean needsErasure = mentionsErased(node, eraser);
                    if (!changed && !needsErasure) return null;
                    // Erased classes are only referenced by this class, never its own nest: drop
                    // their InnerClasses entries (remapping them would make an outer class its own inner).
                    if (needsErasure) node.innerClasses.removeIf(ic -> !eraser.map(ic.name).equals(ic.name));
                    ClassWriter writer = new TclClassWriter(ClassWriter.COMPUTE_MAXS, tcl);
                    node.accept(needsErasure ? new org.objectweb.asm.commons.ClassRemapper(writer, eraser) : writer);
                    return writer.toByteArray();
                } catch (Throwable t) {
                    LOGGER.error("Could not rewrite {} for hot-loaded mixins", name, t);
                    return null;
                }
            }
        }, false);
    }
}
