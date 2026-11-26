package dev.conduit.hotload;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.Set;

/**
 * Replays NeoForge's one-time startup events for a hot-loaded mod, the same way
 * NeoForge runs them at startup, but posted to that mod only. Events that NeoForge fires
 * again later (model and shader reloads, creative tabs…) reach the mod by themselves,
 * because it is in {@code ModList}.
 */
final class Lifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HotLifecycle");

    private Lifecycle() {}

    /** Static initialisers create custom registries during construction: allow it again. */
    static void openRegistryCreation() {
        if (Fields.getStatic(RegistryManager.class, "pendingModdedRegistries") == null) {
            Fields.setStatic(RegistryManager.class, "pendingModdedRegistries", new HashSet<>());
        }
        // Some mods register their registries straight into the root registry while constructing
        // (it is unfrozen at that point during normal startup). Freezing happens in GameData.freezeData.
        ((net.minecraft.core.MappedRegistry<?>) net.minecraft.core.registries.BuiltInRegistries.REGISTRY).unfreeze();
    }

    /** NewRegistryEvent + DataPackRegistryEvent.NewRegistry for the new mod, then close creation again. */
    static void newRegistries(ModContainer container) throws Throwable {
        NewRegistryEvent event = construct(NewRegistryEvent.class);
        post(container, event);
        invoke(event, "fill");
        DataPackRegistryEvent.NewRegistry dataPack = new DataPackRegistryEvent.NewRegistry();
        post(container, dataPack);
        invoke(dataPack, "process");
        post(container, construct(net.neoforged.neoforge.registries.ModifyRegistriesEvent.class));

        Set<?> pending = Fields.getStatic(RegistryManager.class, "pendingModdedRegistries");
        if (pending != null) {
            pending.removeIf(name -> net.minecraft.core.registries.BuiltInRegistries.REGISTRY
                    .containsKey((net.minecraft.resources.ResourceLocation) name));
            if (!pending.isEmpty()) LOGGER.warn("{} created registries it never registered: {}", container.getModId(), pending);
        }
        Fields.setStatic(RegistryManager.class, "pendingModdedRegistries", null);
    }

    /** CLIENT and COMMON configs, as NeoForge loads them after registry init (SERVER ones load per world). */
    static void loadConfigs(ModContainer container) throws Throwable {
        Object tracker = net.neoforged.fml.config.ConfigTracker.INSTANCE;
        java.util.Map<String, java.util.List<net.neoforged.fml.config.ModConfig>> byMod = Fields.get(tracker, "configsByMod");
        java.util.List<net.neoforged.fml.config.ModConfig> configs = byMod.get(container.getModId());
        if (configs == null) return;
        MethodHandle open = HotLoader.implLookup().findStatic(net.neoforged.fml.config.ConfigTracker.class, "openConfig",
                java.lang.invoke.MethodType.methodType(void.class, net.neoforged.fml.config.ModConfig.class,
                        java.nio.file.Path.class, java.nio.file.Path.class));
        boolean client = net.neoforged.fml.loading.FMLEnvironment.dist.isClient();
        for (var config : configs) {
            var type = config.getType();
            if (type == net.neoforged.fml.config.ModConfig.Type.COMMON
                    || (client && type == net.neoforged.fml.config.ModConfig.Type.CLIENT)) {
                open.invoke(config, net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get(), (java.nio.file.Path) null);
            }
        }
    }

    /** One replay step; failures are logged and reported, the rest still runs. */
    private interface Step { void run() throws Throwable; }

    private static void step(java.util.List<String> report, String name, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            LOGGER.error("Lifecycle replay step '{}' failed", name, t);
            report.add(name + ": " + t);
        }
    }

    /**
     * The startup events NeoForge fires once, replayed for a batch of hot-loaded mods, in startup
     * order. Must run before the resource reload (renderers, particles and models get built then).
     * Returns the failures.
     */
    static java.util.List<String> replay(java.util.List<? extends ModContainer> mods) {
        java.util.List<String> failures = new java.util.ArrayList<>();
        boolean client = net.neoforged.fml.loading.FMLEnvironment.dist.isClient();
        // Some registration APIs only work "during client loading" (e.g. render layers).
        Class<?> clientLoader = client ? net.neoforged.neoforge.client.loading.ClientModLoader.class : null;
        if (client) Fields.setStaticBoolean(clientLoader, "loading", true);
        try {
            replaySteps(mods, failures, client);
        } finally {
            if (client) Fields.setStaticBoolean(clientLoader, "loading", false);
        }
        return failures;
    }

    private static void replaySteps(java.util.List<? extends ModContainer> mods, java.util.List<String> failures, boolean client) {

        step(failures, "capabilities", () -> {
            Class<?> hooks = net.neoforged.neoforge.capabilities.CapabilityHooks.class;
            Fields.setStaticBoolean(hooks, "initFinished", false);
            try {
                var event = construct(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class);
                for (ModContainer c : mods) post(c, event);
            } finally {
                Fields.setStaticBoolean(hooks, "initFinished", true);
            }
        });
        step(failures, "block entity blocks", () -> {
            var event = new net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent();
            for (ModContainer c : mods) post(c, event);
        });
        if (client) clientRegistrations(mods, failures);
        parallelPhase(mods, failures, "common setup", net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent::new);
        if (client) parallelPhase(mods, failures, "client setup", net.neoforged.fml.event.lifecycle.FMLClientSetupEvent::new);
        parallelPhase(mods, failures, "IMC enqueue", net.neoforged.fml.event.lifecycle.InterModEnqueueEvent::new);
        parallelPhase(mods, failures, "IMC process", net.neoforged.fml.event.lifecycle.InterModProcessEvent::new);
        parallelPhase(mods, failures, "load complete", net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent::new);
        step(failures, "network payloads", () -> {
            Class<?> registry = net.neoforged.neoforge.network.registration.NetworkRegistry.class;
            Fields.setStaticBoolean(registry, "setup", false);
            try {
                var event = new net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent();
                for (ModContainer c : mods) post(c, event);
            } finally {
                Fields.setStaticBoolean(registry, "setup", true);
            }
        });
    }

    private static <T extends net.neoforged.fml.event.lifecycle.ParallelDispatchEvent> void parallelPhase(
            java.util.List<? extends ModContainer> mods, java.util.List<String> failures, String name,
            java.util.function.BiFunction<ModContainer, net.neoforged.fml.DeferredWorkQueue, T> factory) {
        step(failures, name, () -> {
            var queue = new net.neoforged.fml.DeferredWorkQueue(name);
            for (ModContainer c : mods) post(c, factory.apply(c, queue));
            queue.runTasks(); // enqueueWork(...) tasks run on this (the main) thread, as at startup
        });
    }

    private static void clientRegistrations(java.util.List<? extends ModContainer> mods, java.util.List<String> failures) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ModContainer c : mods) ids.add(c.getModId());

        step(failures, "client extensions", () -> {
            Class<?> manager = net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager.class;
            MethodHandle register = HotLoader.implLookup().findStatic(manager, "register", java.lang.invoke.MethodType.methodType(
                    void.class, Object.class, java.util.Map.class, Object[].class));
            java.util.Map<Object, Object> blocks = Fields.getStatic(manager, "BLOCK_EXTENSIONS");
            java.util.Map<Object, Object> items = Fields.getStatic(manager, "ITEM_EXTENSIONS");
            for (var e : net.minecraft.core.registries.BuiltInRegistries.BLOCK.entrySet()) {
                if (!ids.contains(e.getKey().location().getNamespace())) continue;
                var block = e.getValue();
                block.initializeClient(ext -> invokeQuietly(register, ext, blocks, new Object[]{block}));
            }
            for (var e : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
                if (!ids.contains(e.getKey().location().getNamespace())) continue;
                var item = e.getValue();
                item.initializeClient(ext -> invokeQuietly(register, ext, items, new Object[]{item}));
            }
            var event = construct(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent.class);
            for (ModContainer c : mods) post(c, event);
        });
        step(failures, "menu screens", () -> {
            java.util.Map<?, ?> screens = Fields.getStatic(net.minecraft.client.gui.screens.MenuScreens.class, "SCREENS");
            var event = construct(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent.class, screens);
            for (ModContainer c : mods) post(c, event);
        });
        step(failures, "reload listeners", () -> {
            var event = construct(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent.class, mc.getResourceManager());
            for (ModContainer c : mods) post(c, event);
        });
        step(failures, "entity layer definitions", () -> {
            var event = construct(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions.class);
            for (ModContainer c : mods) post(c, event);
        });
        step(failures, "entity + block entity renderers", () -> {
            var event = construct(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers.class);
            for (ModContainer c : mods) post(c, event);
        });
        step(failures, "key mappings", () -> {
            var event = new net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent(mc.options);
            for (ModContainer c : mods) post(c, event);
            net.minecraft.client.KeyMapping.resetMapping();
        });
        step(failures, "particle providers", () -> {
            var event = new net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent(mc.particleEngine);
            for (ModContainer c : mods) post(c, event);
        });
        step(failures, "color handlers", () -> {
            var blockColors = mc.getBlockColors();
            Object itemColors = Fields.get(mc, "itemColors");
            var blocks = construct(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block.class, blockColors);
            for (ModContainer c : mods) post(c, blocks);
            var items = construct(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item.class, itemColors, blockColors);
            for (ModContainer c : mods) post(c, items);
        });
    }

    private static void invokeQuietly(MethodHandle handle, Object... args) {
        try {
            handle.invokeWithArguments(args);
        } catch (Throwable t) {
            LOGGER.warn("Client extension registration failed", t);
        }
    }

    static <T extends Event & IModBusEvent> void post(ModContainer container, T event) {
        net.neoforged.fml.ModLoadingContext.get().setActiveContainer(container);
        try {
            container.acceptEvent(event);
        } finally {
            net.neoforged.fml.ModLoadingContext.get().setActiveContainer(null);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T construct(Class<T> type, Object... args) throws Throwable {
        for (var ctor : type.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != args.length) continue;
            MethodHandle h = HotLoader.implLookup().unreflectConstructor(ctor);
            return (T) h.invokeWithArguments(args);
        }
        throw new NoSuchMethodException(type.getName() + " constructor with " + args.length + " args");
    }

    static Object invoke(Object target, String name, Object... args) throws Throwable {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    MethodHandle h = HotLoader.implLookup().unreflect(m);
                    Object[] all = new Object[args.length + 1];
                    all[0] = target;
                    System.arraycopy(args, 0, all, 1, args.length);
                    return h.invokeWithArguments(all);
                }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
