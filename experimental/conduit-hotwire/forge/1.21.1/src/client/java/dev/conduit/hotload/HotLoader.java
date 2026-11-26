package dev.conduit.hotload;

import cpw.mods.jarhandling.SecureJar;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.ObjectHolderRegistry;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryManager;
import net.minecraftforge.securemodules.SecureModuleFinder;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads one Forge mod jar into the running game.
 *
 * <ol>
 *   <li>Read {@code mods.toml} and scan annotations, exactly as FML does at startup.</li>
 *   <li>Resolve the jar as a module in a child of the game layer, and teach the game's
 *       {@code TransformingClassLoader} about it, so the mod's classes load through the same
 *       transformer pipeline (mixins, access transformers) as every other mod.</li>
 *   <li>Build an {@code FMLModContainer}, construct the mod, add it to {@link ModList}.</li>
 *   <li>Unfreeze registries, post {@link RegisterEvent}s to the new mod only, freeze again.</li>
 * </ol>
 */
public final class HotLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HotLoad");

    public record Loaded(ModFile modFile, ModInfo info, FMLModContainer container,
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
        List<Loaded> loaded = new ArrayList<>();
        Lifecycle.openNetworking();
        try {
            for (Path p : JarInJar.expand(jar, extractDir)) {
                if (!JarInJar.isMod(p)) {
                    loadLibrary(p);
                    continue;
                }
                Loaded l = load(p);
                if (l != null) loaded.add(l);
            }
            // One-time startup events, for the whole batch (before the resource reload).
            List<String> failures = Lifecycle.replay(loaded.stream().map(Loaded::container).toList());
            if (!failures.isEmpty()) LOGGER.warn("Lifecycle replay had {} failing step(s): {}", failures.size(), failures);
        } finally {
            Lifecycle.closeNetworking();
        }
        return loaded;
    }

    /**
     * The same check FML's ModSorter makes at startup: every mandatory dependency is loaded (or
     * was hot-loaded earlier in this batch) at a version inside the declared range.
     */
    private static void checkDependencies(ModInfo info) {
        List<String> problems = new ArrayList<>();
        boolean onlyMissing = true;
        for (var dep : info.getDependencies()) {
            if (!dep.isMandatory()) continue;
            if (dep.getSide() != null && !dep.getSide().isCorrectSide()) continue;
            var present = ModList.get().getModContainerById(dep.getModId());
            if (present.isEmpty()) {
                problems.add(dep.getModId() + " " + dep.getVersionRange() + " is missing");
            } else if (!dep.getVersionRange().containsVersion(present.get().getModInfo().getVersion())) {
                onlyMissing = false;
                problems.add(dep.getModId() + " " + dep.getVersionRange() + " is required, "
                        + present.get().getModInfo().getVersion() + " is loaded");
            }
        }
        if (!problems.isEmpty()) {
            String message = "Can't hot-load " + info.getModId() + ": " + String.join("; ", problems);
            if (onlyMissing) throw new MissingDependencyException(message);
            throw new IllegalStateException(message);
        }
    }

    /** A required mod isn't loaded (yet): loading another jar first may fix it. */
    public static final class MissingDependencyException extends IllegalStateException {
        MissingDependencyException(String message) { super(message); }
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

    /** Builds FML's ModFile for a jar the same way the mods-folder locator does. */
    private static final class HotModProvider extends AbstractModProvider {
        @Override
        public String name() {
            return "conduit hot-load";
        }

        ModFile create(Path jar) {
            var result = createMod(jar);
            if (result == null || result.file() == null) {
                throw new IllegalArgumentException(jar + " is not a Forge mod"
                        + (result != null && result.ex() != null ? ": " + result.ex().getMessage() : ""));
            }
            return (ModFile) result.file();
        }
    }

    private static final HotModProvider PROVIDER = new HotModProvider();

    /** Loads one mod jar; returns null if a mod with the same id is already loaded. */
    public static Loaded load(Path jar) throws Throwable {
        long start = System.nanoTime();

        // 1. Mod metadata + annotation scan, as FML's discovery does.
        ModFile modFile = PROVIDER.create(jar);
        if (!modFile.identifyMods()) throw new IllegalStateException("Could not read mods.toml in " + jar);
        // FML's annotation scanner (package-private in Forge).
        Class<?> scanner = Class.forName("net.minecraftforge.fml.loading.moddiscovery.Scanner");
        Object scanning = implLookup().findConstructor(scanner, MethodType.methodType(void.class, ModFile.class)).invoke(modFile);
        ModFileScanData scan = (ModFileScanData) implLookup().findVirtual(scanner, "scan",
                MethodType.methodType(ModFileScanData.class)).invoke(scanning);
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

        // 3. Container, construction, mod list. Forge has one @Mod class per mod.
        Type modAnnotation = Type.getType(Mod.class);
        String entrypoint = scan.getAnnotations().stream()
                .filter(a -> modAnnotation.equals(a.annotationType()) && a.targetType() == ElementType.TYPE)
                .filter(a -> info.getModId().equals(a.annotationData().get("value")))
                .map(a -> a.clazz().getClassName())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No @Mod(\"" + info.getModId() + "\") class in " + jar));
        FMLModContainer container = new FMLModContainer(info, entrypoint, scan, layer);
        // FML lists every mod before constructing any (mods look themselves up while constructing).
        addToModList(modFile, info, container, scan);
        ModLoadingContext context = ModLoadingContext.get();
        context.setActiveContainer(container);
        try {
            implLookup().findVirtual(FMLModContainer.class, "constructMod", MethodType.methodType(void.class))
                    .invoke(container);
        } finally {
            context.setActiveContainer(null);
        }
        Lifecycle.newRegistries(container);

        // 4. Registries, then configs (Forge's startup order).
        register(container);
        Lifecycle.loadConfigs(container);

        LOGGER.info("Hot-loaded {} {} in {} ms", info.getModId(), info.getVersion(),
                (System.nanoTime() - start) / 1_000_000);
        return new Loaded(modFile, info, container, mixins);
    }

    /**
     * Resolves the jar as a module whose parent is the game layer, defines it to the game's
     * {@code TransformingClassLoader}, and adds it to that loader's lookup tables so it can find
     * the new module's classes and resources (parents stay visible).
     */
    private static ModuleLayer defineModule(SecureJar jar) {
        ModuleLayer parentLayer = topLayer != null ? topLayer : FMLLoader.getGameLayer();
        ClassLoader tcl = HotLoader.class.getClassLoader();
        if (Fields.get(tcl, "configuration") != parentLayer.configuration()) {
            throw new IllegalStateException("Game classloader configuration does not match the top module layer");
        }
        Configuration child = Configuration.resolve(SecureModuleFinder.of(jar), List.of(parentLayer.configuration()),
                ModuleFinder.of(), List.of(jar.name()));
        ModuleLayer layer = ModuleLayer.defineModules(child, List.of(parentLayer), name -> tcl).layer();

        ResolvedModule module = child.findModule(jar.name()).orElseThrow();
        ModuleReference ref = module.reference();
        Map<String, ResolvedModule> packages = Fields.get(tcl, "packageToOurModules");
        Map<String, ModuleReference> modules = Fields.get(tcl, "ourModules");
        Map<String, Object> secureModules = Fields.get(tcl, "ourModulesSecure");
        synchronized (tcl) {
            for (String pkg : ref.descriptor().packages()) {
                if (packages.containsKey(pkg)) throw new IllegalStateException("Package " + pkg + " is already loaded");
                packages.put(pkg, module);
            }
            modules.put(jar.name(), ref);
            if (ref instanceof net.minecraftforge.securemodules.SecureModuleReference) secureModules.put(jar.name(), ref);
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

    /** Runs the registration phase again, for this mod's event bus only, in Forge's order. */
    @SuppressWarnings("deprecation")
    private static void register(FMLModContainer container) throws Throwable {
        MethodHandle newEvent = implLookup().findConstructor(RegisterEvent.class,
                MethodType.methodType(void.class, ResourceKey.class, ForgeRegistry.class, Registry.class));
        Set<ResourceLocation> keys = new LinkedHashSet<>(Fields.<Map<ResourceLocation, ?>>get(RegistryManager.ACTIVE, "registries").keySet());
        keys.addAll(RegistryManager.getVanillaRegistryKeys());
        Set<ResourceLocation> order = new LinkedHashSet<>(net.minecraft.core.MappedRegistry.getKnownRegistries());
        order.retainAll(keys);
        order.addAll(keys.stream().sorted(ResourceLocation::compareNamespaced).toList());

        // A mod can switch on Forge's optional milk fluid while constructing. Forge registers it
        // from its own RegisterEvent listener, which already ran: run it again with these events.
        Class<?> forgeMod = net.minecraftforge.common.ForgeMod.class;
        boolean addMilk = Fields.getStaticBoolean(forgeMod, "enableMilkFluid")
                && !BuiltInRegistries.FLUID.containsKey(ResourceLocation.withDefaultNamespace("milk"));
        Object forge = addMilk ? Fields.allocate(forgeMod) : null; // registerFluids only reads static state

        GameData.unfreezeData();
        try {
            for (ResourceLocation name : order) {
                ForgeRegistry<?> registry = RegistryManager.ACTIVE.getRegistry(name);
                Registry<?> vanilla = BuiltInRegistries.REGISTRY.get(name);
                RegisterEvent event = (RegisterEvent) newEvent.invoke(ResourceKey.createRegistryKey(name), registry, vanilla);
                if (registry != null) implLookup().findVirtual(ForgeRegistry.class, "unfreeze", MethodType.methodType(void.class)).invoke(registry);
                try {
                    Lifecycle.post(container, event);
                    if (addMilk) ((net.minecraftforge.common.ForgeMod) forge).registerFluids(event);
                } finally {
                    if (registry != null) implLookup().findVirtual(ForgeRegistry.class, "freeze", MethodType.methodType(void.class)).invoke(registry);
                }
                ObjectHolderRegistry.applyObjectHolders(name);
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
