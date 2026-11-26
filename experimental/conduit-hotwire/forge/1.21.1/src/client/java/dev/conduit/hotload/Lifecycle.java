package dev.conduit.hotload;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.registries.DataPackRegistryEvent;
import net.minecraftforge.registries.NewRegistryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays Forge's one-time startup events for a hot-loaded mod, the same way Forge runs them at
 * startup, but posted to that mod only. Events that Forge fires again later (model and shader
 * reloads, creative tabs…) reach the mod by themselves, because it is in {@code ModList}.
 */
final class Lifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HotLifecycle");

    private Lifecycle() {}

    /** Forge locks channel registration after startup; mods create channels while loading. */
    static void openNetworking() {
        Fields.setStaticBoolean(net.minecraftforge.network.NetworkRegistry.class, "lock", false);
    }

    static void closeNetworking() {
        Fields.setStaticBoolean(net.minecraftforge.network.NetworkRegistry.class, "lock", true);
    }

    /** Some mods write their registries straight into the root registry while constructing. */
    static void openRegistryCreation() {
        ((net.minecraft.core.MappedRegistry<?>) net.minecraft.core.registries.BuiltInRegistries.REGISTRY).unfreeze();
    }

    /** NewRegistryEvent + DataPackRegistryEvent.NewRegistry for the new mod (Forge's CREATE_REGISTRIES). */
    static void newRegistries(FMLModContainer container) throws Throwable {
        NewRegistryEvent event = new NewRegistryEvent();
        DataPackRegistryEvent.NewRegistry dataPack = new DataPackRegistryEvent.NewRegistry();
        post(container, event);
        post(container, dataPack);
        invoke(event, "fill");
        invoke(dataPack, "process");
    }

    /** CLIENT and COMMON configs, as Forge loads them after registry init (SERVER ones load per world). */
    static void loadConfigs(ModContainer container) throws Throwable {
        Object tracker = net.minecraftforge.fml.config.ConfigTracker.INSTANCE;
        Map<String, Map<net.minecraftforge.fml.config.ModConfig.Type, net.minecraftforge.fml.config.ModConfig>> byMod =
                Fields.get(tracker, "configsByMod");
        var configs = byMod.get(container.getModId());
        if (configs == null) return;
        MethodHandle open = HotLoader.implLookup().findStatic(net.minecraftforge.fml.config.ConfigTracker.class, "openConfig",
                MethodType.methodType(void.class, net.minecraftforge.fml.config.ModConfig.class, java.nio.file.Path.class));
        boolean client = net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient();
        for (var config : configs.values()) {
            var type = config.getType();
            if (type == net.minecraftforge.fml.config.ModConfig.Type.COMMON
                    || (client && type == net.minecraftforge.fml.config.ModConfig.Type.CLIENT)) {
                open.invoke(config, net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
            }
        }
    }

    /** One replay step; failures are logged and reported, the rest still runs. */
    private interface Step { void run() throws Throwable; }

    private static void step(List<String> report, String name, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            LOGGER.error("Lifecycle replay step '{}' failed", name, t);
            report.add(name + ": " + t);
        }
    }

    /**
     * The startup events Forge fires once, replayed for a batch of hot-loaded mods, in startup
     * order. Must run before the resource reload (renderers, particles and models get built then).
     * Returns the failures.
     */
    static List<String> replay(List<? extends FMLModContainer> mods) {
        List<String> failures = new java.util.ArrayList<>();
        boolean client = net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient();
        // Some registration APIs only work "during client loading" (e.g. render layers).
        Class<?> clientLoader = client ? net.minecraftforge.client.loading.ClientModLoader.class : null;
        if (client) Fields.setStaticBoolean(clientLoader, "loading", true);
        try {
            replaySteps(mods, failures, client);
        } finally {
            if (client) Fields.setStaticBoolean(clientLoader, "loading", false);
        }
        return failures;
    }

    private static void replaySteps(List<? extends FMLModContainer> mods, List<String> failures, boolean client) {
        step(failures, "capabilities", () -> {
            var event = construct(net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent.class);
            for (FMLModContainer c : mods) post(c, event);
        });
        if (client) clientRegistrations(mods, failures);
        parallelPhase(mods, failures, "common setup", ModLoadingStage.COMMON_SETUP,
                net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent::new);
        if (client) parallelPhase(mods, failures, "client setup", ModLoadingStage.SIDED_SETUP,
                net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent::new);
        parallelPhase(mods, failures, "IMC enqueue", ModLoadingStage.ENQUEUE_IMC,
                net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent::new);
        parallelPhase(mods, failures, "IMC process", ModLoadingStage.PROCESS_IMC,
                net.minecraftforge.fml.event.lifecycle.InterModProcessEvent::new);
        parallelPhase(mods, failures, "load complete", ModLoadingStage.COMPLETE,
                net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent::new);
    }

    private static <T extends net.minecraftforge.fml.event.lifecycle.ParallelDispatchEvent> void parallelPhase(
            List<? extends FMLModContainer> mods, List<String> failures, String name, ModLoadingStage stage,
            java.util.function.BiFunction<ModContainer, ModLoadingStage, T> factory) {
        step(failures, name, () -> {
            var queue = new net.minecraftforge.fml.DeferredWorkQueue(stage); // replaces the finished startup queue
            for (FMLModContainer c : mods) post(c, factory.apply(c, stage));
            queue.runTasks(); // enqueueWork(...) tasks run on this (the main) thread, as at startup
        });
    }

    private static void clientRegistrations(List<? extends FMLModContainer> mods, List<String> failures) {
        var mc = net.minecraft.client.Minecraft.getInstance();

        step(failures, "reload listeners", () -> {
            var event = new net.minecraftforge.client.event.RegisterClientReloadListenersEvent(
                    (net.minecraft.server.packs.resources.ReloadableResourceManager) mc.getResourceManager());
            for (FMLModContainer c : mods) post(c, event);
        });
        step(failures, "entity layer definitions", () -> {
            var event = new net.minecraftforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions();
            for (FMLModContainer c : mods) post(c, event);
        });
        step(failures, "entity + block entity renderers", () -> {
            var event = new net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers();
            for (FMLModContainer c : mods) post(c, event);
        });
        step(failures, "key mappings", () -> {
            var event = new net.minecraftforge.client.event.RegisterKeyMappingsEvent(mc.options);
            for (FMLModContainer c : mods) post(c, event);
            net.minecraft.client.KeyMapping.resetMapping();
        });
        step(failures, "particle providers", () -> {
            var event = new net.minecraftforge.client.event.RegisterParticleProvidersEvent(mc.particleEngine);
            for (FMLModContainer c : mods) post(c, event);
        });
        step(failures, "color handlers", () -> {
            var blockColors = mc.getBlockColors();
            net.minecraft.client.color.item.ItemColors itemColors = Fields.get(mc, "itemColors");
            var blocks = new net.minecraftforge.client.event.RegisterColorHandlersEvent.Block(blockColors);
            for (FMLModContainer c : mods) post(c, blocks);
            var items = new net.minecraftforge.client.event.RegisterColorHandlersEvent.Item(itemColors, blockColors);
            for (FMLModContainer c : mods) post(c, items);
        });
        step(failures, "tooltip components", () -> {
            Class<?> manager = net.minecraftforge.client.gui.ClientTooltipComponentManager.class;
            Map<Class<?>, Object> factories = new java.util.HashMap<>(Fields.<Map<Class<?>, Object>>getStatic(manager, "FACTORIES"));
            var event = construct(net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent.class, factories);
            for (FMLModContainer c : mods) post(c, event);
            Fields.setStatic(manager, "FACTORIES", com.google.common.collect.ImmutableMap.copyOf(factories));
        });
    }

    static <T extends Event & IModBusEvent> void post(FMLModContainer container, T event) {
        net.minecraftforge.fml.ModLoadingContext.get().setActiveContainer(container);
        try {
            container.getEventBus().post(event);
        } finally {
            net.minecraftforge.fml.ModLoadingContext.get().setActiveContainer(null);
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
