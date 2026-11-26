package dev.conduit.hotload;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.fml.loading.modscan.Scanner;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads one NeoForge mod jar into the running game.
 *
 * <ol>
 *   <li>Parse {@code neoforge.mods.toml} and scan annotations, exactly as FML does at startup.</li>
 *   <li>Resolve the jar as a module in a child of the game layer, and teach the game's
 *       {@code TransformingClassLoader} about it, so the mod's classes load through the same
 *       transformer pipeline (mixins, access transformers) as every other mod.</li>
 *   <li>Build an {@code FMLModContainer}, construct the mod, add it to {@link ModList}.</li>
 *   <li>Unfreeze registries, post {@link RegisterEvent}s to the new mod only, freeze again.</li>
 * </ol>
 */
public final class HotLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HotLoad");

    public record Loaded(ModFile modFile, ModInfo info, HotContainer container,
                         dev.conduit.hotload.mixin.HotMixins.Outcome mixins) {}

    /** Newest layer: each hot-loaded mod gets a child of the previous one. */
    private static ModuleLayer topLayer;

    private HotLoader() {}

    /**
     * Loads a jar and everything it bundles (jar-in-jar): libraries become modules, mods are
     * loaded in order (bundled ones first). Already-present mods and libraries are skipped.
     */
    public static List<Loaded> loadAll(Path jar) throws Throwable {
        Path extractDir = FMLLoader.getGamePath().resolve(".conduit").resolve("hotload-jij");
        List<Loaded> loaded = new java.util.ArrayList<>();
        for (Path p : JarInJar.expand(jar, extractDir)) {
            if (!JarInJar.isMod(p)) {
                loadLibrary(p);
                continue;
            }
            Loaded l = load(p);
            if (l != null) loaded.add(l);
        }
        // 5. One-time startup events, for the whole batch (before the resource reload).
        List<String> failures = Lifecycle.replay(loaded.stream().map(Loaded::container).toList());
        if (!failures.isEmpty()) LOGGER.warn("Lifecycle replay had {} failing step(s): {}", failures.size(), failures);
        return loaded;
    }

    /**
     * The same check FML's ModSorter makes at startup: every required dependency is loaded (or
     * was hot-loaded earlier in this batch) at a version inside the declared range.
     */
    private static void checkDependencies(ModInfo info) {
        List<String> problems = new java.util.ArrayList<>();
        for (var dep : info.getDependencies()) {
            if (dep.getType() != net.neoforged.neoforgespi.language.IModInfo.DependencyType.REQUIRED) continue;
            if (dep.getSide() != null && !dep.getSide().isCorrectSide()) continue;
            var present = ModList.get().getModContainerById(dep.getModId());
            if (present.isEmpty()) {
                problems.add(dep.getModId() + " " + dep.getVersionRange() + " is missing");
            } else if (!dep.getVersionRange().containsVersion(present.get().getModInfo().getVersion())) {
                problems.add(dep.getModId() + " " + dep.getVersionRange() + " is required, "
                        + present.get().getModInfo().getVersion() + " is loaded");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Can't hot-load " + info.getModId() + ": " + String.join("; ", problems));
        }
    }

    /** A plain library jar (no mods.toml): just a module the mods can use. */
    private static void loadLibrary(Path jar) throws Exception {
        SecureJar secure = SecureJar.from(jar);
        Configuration current = Fields.get(HotLoader.class.getClassLoader(), "configuration");
        if (current.findModule(secure.name()).isPresent()) {
            LOGGER.info("Library {} is already present", secure.name());
            return;
        }
        ModuleLayer layer = defineModule(secure);
        readableByAll(layer, layer.findModule(secure.name()).orElseThrow());
        LOGGER.info("Hot-loaded library module {}", secure.name());
    }

    /**
     * Game modules never read hot-loaded ones, but mixins and companions (in game modules) end up
     * referring to the new module's classes. Let every module in the game and hot layers read it.
     */
    private static void readableByAll(ModuleLayer layer, Module module) throws Exception {
        for (ModuleLayer l = layer; l != null; l = l.parents().isEmpty() ? null : l.parents().get(0)) {
            for (Module m : l.modules()) if (m != module) dev.conduit.hotload.jvm.JvmAccess.addReads(m, module);
            if (l == FMLLoader.getGameLayer()) break;
        }
    }

    /** Loads one mod jar; returns null if a mod with the same id is already loaded. */
    public static Loaded load(Path jar) throws Throwable {
        long start = System.nanoTime();

        // 1. Mod metadata + annotation scan, as FML's discovery does.
        ModFile modFile = (ModFile) JarModsDotTomlModFileReader.createModFile(JarContents.of(jar),
                ModFileDiscoveryAttributes.DEFAULT);
        if (modFile == null) throw new IllegalArgumentException(jar + " is not a NeoForge mod");
        if (!modFile.identifyMods()) throw new IllegalStateException("Could not read mods.toml in " + jar);
        ModFileScanData scan = new Scanner(modFile).scan();
        modFile.setScanResult(scan, null);
        ModInfo info = (ModInfo) modFile.getModInfos().get(0);
        if (ModList.get().isLoaded(info.getModId())) {
            LOGGER.info("{} is already loaded; skipping", info.getModId());
            return null;
        }
        checkDependencies(info);

        // 2. Module layer + classloader.
        ModuleLayer layer = defineModule(modFile.getSecureJar());
        Module module = layer.findModule(modFile.getSecureJar().name()).orElseThrow();
        readableByAll(layer, module);

        // Mod code (even mixin static init during the split) may create registries from here on.
        Lifecycle.openRegistryCreation();

        // 2a. Access transformers: the AT engine applies them to classes that load from now on;
        //     for already-loaded classes the mod's accesses get rerouted (AccessRegistry).
        for (Path at : modFile.getAccessTransformers()) {
            FMLLoader.addAccessTransformer(at, modFile);
            dev.conduit.hotload.mixin.AccessRegistry.load(at);
        }

        // 2b. Mixins: before any of the mod's classes load, so they get rewritten as needed.
        dev.conduit.hotload.mixin.HotMixins.Outcome mixins = dev.conduit.hotload.mixin.HotMixins.apply(modFile, module);

        // 2c. Final methods the ATs un-final on already-loaded classes: dispatch shim.
        if (!modFile.getAccessTransformers().isEmpty()) {
            dev.conduit.hotload.mixin.FinalShim.apply(dev.conduit.hotload.jvm.JvmAccess.instrumentation(),
                    HotLoader.class.getClassLoader());
        }

        // 3. Container, construction, mod list.
        List<String> entrypoints = scan.getAnnotatedBy(Mod.class, ElementType.TYPE)
                .filter(a -> info.getModId().equals(a.annotationData().get("value")))
                .map(a -> a.clazz().getClassName())
                .toList();
        HotContainer container = new HotContainer(info, entrypoints, scan, layer);
        // FML lists every mod before constructing any (mods look themselves up while constructing).
        addToModList(modFile, info, container, scan);
        ModLoadingContext context = ModLoadingContext.get();
        context.setActiveContainer(container);
        try {
            container.construct();
        } finally {
            context.setActiveContainer(null);
        }
        Lifecycle.newRegistries(container);

        // 4. Registries, then configs (NeoForge's startup order).
        register(container);
        Lifecycle.loadConfigs(container);

        LOGGER.info("Hot-loaded {} {} in {} ms", info.getModId(), info.getVersion(),
                (System.nanoTime() - start) / 1_000_000);
        return new Loaded(modFile, info, container, mixins);
    }

    /**
     * Resolves the jar as a module whose parent is the game layer, defines it to the
     * game's {@code TransformingClassLoader}, and swaps that loader's configuration for
     * the child one so it can find the new module (parents stay visible).
     */
    private static ModuleLayer defineModule(SecureJar jar) {
        ModuleLayer parentLayer = topLayer != null ? topLayer : FMLLoader.getGameLayer();
        ClassLoader tcl = HotLoader.class.getClassLoader();
        if (Fields.get(tcl, "configuration") != parentLayer.configuration()) {
            throw new IllegalStateException("Game classloader configuration does not match the top module layer");
        }
        Configuration child = Configuration.resolve(JarModuleFinder.of(jar), List.of(parentLayer.configuration()),
                ModuleFinder.of(), List.of(jar.name()));
        ModuleLayer layer = ModuleLayer.defineModules(child, List.of(parentLayer), name -> tcl).layer();

        ResolvedModule module = child.findModule(jar.name()).orElseThrow();
        Map<String, ResolvedModule> packageLookup = Fields.get(tcl, "packageLookup");
        Map<String, Object> resolvedRoots = Fields.get(tcl, "resolvedRoots");
        synchronized (tcl) {
            for (String pkg : module.reference().descriptor().packages()) {
                if (packageLookup.containsKey(pkg)) throw new IllegalStateException("Package " + pkg + " is already loaded");
                packageLookup.put(pkg, module);
            }
            resolvedRoots.put(jar.name(), module.reference());
            Fields.set(tcl, "configuration", child);
        }
        topLayer = layer;
        return layer;
    }

    private static void addToModList(ModFile modFile, ModInfo info, ModContainer container, ModFileScanData scan) {
        ModList list = ModList.get();
        Fields.set(list, "mods", append(Fields.get(list, "mods"), container));
        Fields.set(list, "sortedContainers", append(Fields.get(list, "sortedContainers"), container));
        Fields.set(list, "sortedList", append(Fields.get(list, "sortedList"), (IModInfo) info));
        Fields.set(list, "modFiles", append(Fields.get(list, "modFiles"), modFile.getModFileInfo()));
        Fields.set(list, "modFileScanData", append(Fields.get(list, "modFileScanData"), scan));
        Map<String, ModContainer> indexed = new HashMap<>(Fields.<Map<String, ModContainer>>get(list, "indexedMods"));
        indexed.put(info.getModId(), container);
        Fields.set(list, "indexedMods", indexed);
        Map<String, ModFileInfo> byId = new HashMap<>(Fields.<Map<String, ModFileInfo>>get(list, "fileById"));
        byId.put(info.getModId(), (ModFileInfo) modFile.getModFileInfo());
        Fields.set(list, "fileById", byId);
    }

    private static <T> List<T> append(List<T> list, T value) {
        List<T> out = new ArrayList<>(list == null ? List.of() : list);
        out.add(value);
        return out;
    }

    /** Runs the registration phase again, for this mod's event bus only. */
    private static void register(HotContainer container) throws Throwable {
        MethodHandle newEvent = implLookup().findConstructor(RegisterEvent.class,
                MethodType.methodType(void.class, ResourceKey.class, Registry.class));
        Set<ResourceLocation> order = GameData.getRegistrationOrder();
        // A mod can switch on NeoForge's optional milk fluid while constructing (Create does).
        // NeoForge registers it from its own RegisterEvent listener, which already ran: run it again.
        Class<?> neoMod = net.neoforged.neoforge.common.NeoForgeMod.class;
        boolean addMilk = Fields.getStaticBoolean(neoMod, "enableMilkFluid")
                && !BuiltInRegistries.FLUID.containsKey(ResourceLocation.withDefaultNamespace("milk"));
        Object neoforge = addMilk ? Fields.allocate(neoMod) : null; // registerFluids only reads static state
        GameData.unfreezeData();
        try {
            for (ResourceLocation name : order) {
                Registry<?> registry = BuiltInRegistries.REGISTRY.get(name);
                if (registry == null) continue;
                RegisterEvent event = (RegisterEvent) newEvent.invoke(ResourceKey.createRegistryKey(name), registry);
                container.getEventBus().post(event);
                if (addMilk) ((net.neoforged.neoforge.common.NeoForgeMod) neoforge).registerFluids(event);
            }
        } finally {
            // Freezing takes a new snapshot and fires the "ids are final" remap event.
            GameData.freezeData();
        }
    }

    /** The JDK's trusted lookup, for package-private constructors in loader modules. */
    public static MethodHandles.Lookup implLookup() {
        return Fields.getStatic(MethodHandles.Lookup.class, "IMPL_LOOKUP");
    }
}
