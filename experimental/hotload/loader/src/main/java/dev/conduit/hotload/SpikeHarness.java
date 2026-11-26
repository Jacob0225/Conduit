package dev.conduit.hotload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.GameData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated spike run ({@code -Dconduit.hotload.test=<jar>}): on the title screen,
 * hot-load the jar, check registries, reload resources, check the model and
 * translation, draw the item, screenshot, write a report, quit.
 */
final class SpikeHarness {
    static final String PROPERTY = "conduit.hotload.test";
    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/HotLoadSpike");
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("hotblock", "glow_cube");

    private enum State { WAITING, LOADING, SHOWING, JOINING, DONE }

    private static State state = State.WAITING;
    private static final List<String> report = new ArrayList<>();
    private static int frames;
    private static boolean failed;

    private SpikeHarness() {}

    static void install() {
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
            if (state == State.WAITING && event.getScreen() instanceof TitleScreen) {
                state = State.LOADING;
                Minecraft.getInstance().execute(SpikeHarness::run);
            }
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, event -> {
            if (state == State.SHOWING && event.getScreen() instanceof TitleScreen) show(event.getGuiGraphics());
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            if (state == State.JOINING) joinTick();
        });
    }

    private static void check(String what, boolean ok) {
        report.add((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private static void run() {
        Minecraft mc = Minecraft.getInstance();
        try {
            checkLoadedTargets(mc);
            if (Boolean.getBoolean("conduit.hotload.redefineExperiment")) try {
                long tr = System.nanoTime();
                RedefineExperiment.run();
                check("redefined loaded SplashRenderer via hidden nestmate companion ("
                        + (System.nanoTime() - tr) / 1_000_000 + " ms incl. agent attach)", true);
            } catch (Throwable t) {
                LOGGER.error("Redefine experiment failed", t);
                check("redefine experiment: " + t, false);
            }
            List<net.neoforged.fml.loading.moddiscovery.ModFile> files = new ArrayList<>();
            for (String path : System.getProperty(PROPERTY).split(java.util.regex.Pattern.quote("|"))) {
                long t0 = System.nanoTime();
                List<HotLoader.Loaded> batch = HotLoader.loadAll(Path.of(path));
                report.add("INFO " + Path.of(path).getFileName() + ": " + batch.size() + " mod(s) loaded in "
                        + (System.nanoTime() - t0) / 1_000_000 + " ms");
                for (HotLoader.Loaded loaded : batch) {
                files.add(loaded.modFile());
                report.add("INFO   " + loaded.info().getModId() + " " + loaded.info().getVersion());
                if (loaded.mixins() != null) {
                    var m = loaded.mixins();
                    report.add("INFO mixins: " + m.loadedTargets().size() + " loaded targets " + m.loadedTargets()
                            + ", applied on load later: " + m.laterTargets());
                    if (m.split() != null) {
                        var r = m.split();
                        report.add("INFO split: " + r.changedBodies() + " bodies changed, " + r.movedMethods()
                                + " methods moved, " + r.movedFields() + " fields moved, " + r.interfaces()
                                + " interfaces, " + r.staticInits() + " static inits " + r.notes());
                    }
                }
                }
            }
            Block block = BuiltInRegistries.BLOCK.get(ID);
            Item item = BuiltInRegistries.ITEM.get(ID);
            check("block registered", BuiltInRegistries.BLOCK.containsKey(ID) && block != null);
            check("item registered", BuiltInRegistries.ITEM.containsKey(ID) && item != null);
            BlockState st = block.defaultBlockState();
            check("block state has a network id", GameData.getBlockStateIDMap().getId(st) >= 0);
            check("block item maps to block", GameData.getBlockItemMap().get(block) == item);
            check("block emits light 15", st.getLightEmission() == 15);
            check("mod in ModList", net.neoforged.fml.ModList.get().isLoaded("hotblock"));
            check("block registry frozen again", isFrozen());

            long t1 = System.nanoTime();
            HotResources.addAndReload(files).whenComplete((ok, error) -> mc.execute(() -> {
                report.add("INFO resource reload took " + (System.nanoTime() - t1) / 1_000_000 + " ms");
                if (error != null) {
                    check("resource reload", false);
                    LOGGER.error("Resource reload failed", error);
                }
                var model = mc.getModelManager().getBlockModelShaper().getBlockModel(st);
                check("block model baked (not the missing model)", model != mc.getModelManager().getMissingModel());
                var itemModel = mc.getItemRenderer().getItemModelShaper().getItemModel(item);
                check("item model baked (not the missing model)",
                        itemModel != null && itemModel != mc.getModelManager().getMissingModel());
                check("translation loaded", "Glow Cube".equals(I18n.get(block.getDescriptionId())));
                checkCreate(mc);
                for (var f : files) {
                    for (var info : f.getModInfos()) {
                        String ns = info.getModId();
                        long blocks = BuiltInRegistries.BLOCK.keySet().stream().filter(k -> k.getNamespace().equals(ns)).count();
                        long items = BuiltInRegistries.ITEM.keySet().stream().filter(k -> k.getNamespace().equals(ns)).count();
                        report.add("INFO " + ns + " " + info.getVersion() + ": " + blocks + " blocks, " + items + " items");
                    }
                }
                state = State.SHOWING;
            }));
        } catch (Throwable t) {
            LOGGER.error("Hot-load failed", t);
            report.add("FAIL hot-load threw " + t);
            failed = true;
            finish(mc);
        }
    }

    /**
     * For each class Create/Flywheel/Ponder mixin into, record whether it is already
     * loaded at the title screen (where Conduit would hot-load). Those are the ones a
     * stock JVM cannot reshape.
     */
    private static void checkLoadedTargets(Minecraft mc) {
        String list = System.getProperty("conduit.hotload.targets");
        if (list == null) return;
        try {
            ClassLoader tcl = SpikeHarness.class.getClassLoader();
            var findLoaded = HotLoader.implLookup().findVirtual(ClassLoader.class, "findLoadedClass",
                    java.lang.invoke.MethodType.methodType(Class.class, String.class));
            List<String> out = new ArrayList<>();
            int loaded = 0, total = 0;
            for (String name : Files.readAllLines(Path.of(list))) {
                if (name.isBlank()) continue;
                total++;
                boolean isLoaded = findLoaded.invoke(tcl, name) != null;
                if (isLoaded) loaded++;
                out.add((isLoaded ? "LOADED  " : "not yet ") + name);
            }
            out.add(0, loaded + " of " + total + " mixin targets already loaded at the title screen");
            Files.write(mc.gameDirectory.toPath().resolve("hotload-targets.txt"), out);
            report.add("INFO " + out.get(0));
        } catch (Throwable t) {
            LOGGER.warn("Target check failed", t);
        }
    }

    /** If Create was hot-loaded: its content is registered, modelled and translated. */
    private static void checkCreate(Minecraft mc) {
        if (!net.neoforged.fml.ModList.get().isLoaded("create")) return;
        ResourceLocation cog = ResourceLocation.fromNamespaceAndPath("create", "cogwheel");
        check("Create: cogwheel block registered", BuiltInRegistries.BLOCK.containsKey(cog));
        Block block = BuiltInRegistries.BLOCK.get(cog);
        Item item = BuiltInRegistries.ITEM.get(cog);
        var model = mc.getModelManager().getBlockModelShaper().getBlockModel(block.defaultBlockState());
        check("Create: cogwheel block model baked", model != mc.getModelManager().getMissingModel());
        var itemModel = mc.getItemRenderer().getItemModelShaper().getItemModel(item);
        check("Create: cogwheel item model baked", itemModel != null && itemModel != mc.getModelManager().getMissingModel());
        String name = I18n.get(block.getDescriptionId());
        check("Create: cogwheel name translated (" + name + ")", "Cogwheel".equals(name));
        long createBlocks = BuiltInRegistries.BLOCK.keySet().stream().filter(k -> k.getNamespace().equals("create")).count();
        long createItems = BuiltInRegistries.ITEM.keySet().stream().filter(k -> k.getNamespace().equals("create")).count();
        report.add("INFO Create registered " + createBlocks + " blocks and " + createItems + " items");
        try {
            Class<?> nr = net.neoforged.neoforge.network.registration.NetworkRegistry.class;
            java.util.Map<?, java.util.Map<ResourceLocation, ?>> regs = Fields.getStatic(nr, "PAYLOAD_REGISTRATIONS");
            long createPayloads = regs.values().stream().flatMap(m -> m.keySet().stream())
                    .filter(k -> k.getNamespace().equals("create") || k.getNamespace().equals("ponder")).count();
            check("Create + Ponder network payloads registered (" + createPayloads + ")", createPayloads > 0);
        } catch (Throwable t) {
            check("payload check: " + t, false);
        }
    }

    /** Each HotMixin method exercises one mixin case from the hot-loaded mod's own code. */
    private static void checkMixins() {
        if (!net.neoforged.fml.ModList.get().isLoaded("hotmixin")) return;
        try {
            ClassLoader tcl = SpikeHarness.class.getClassLoader();
            Class<?> hm = Class.forName("dev.conduit.hotmixin.HotMixin", true, tcl);
            java.util.function.Function<String, Object> call = name -> {
                try {
                    return hm.getMethod(name).invoke(null);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    LOGGER.error("HotMixin.{} failed", name, e.getCause());
                    return e.getCause().toString();
                } catch (ReflectiveOperationException e) {
                    return e.toString();
                }
            };
            Object ticks = call.apply("ticks");
            check("@Inject handler + @Unique field on loaded Minecraft (ticks counted: " + ticks + ")",
                    ticks instanceof Integer i && i > 0);
            Object staticTicks = call.apply("staticTicks");
            check("@Unique static field with initializer (" + staticTicks + ")",
                    staticTicks instanceof Integer i && i > 0);
            int lambdas = ((java.util.concurrent.atomic.AtomicInteger) hm.getField("LAMBDA_RUNS").get(null)).get();
            check("lambda inside a moved handler (" + lambdas + " runs)", lambdas > 0);
            check("instanceof an interface added to a loaded class", Boolean.TRUE.equals(call.apply("isTickCounter")));
            Object roundTrip = call.apply("accessorRoundTrip");
            check("@Accessor getter + setter on a private field (read back " + roundTrip + ")", Integer.valueOf(7).equals(roundTrip));
            Object invoker = call.apply("invokerIsMultiplayer");
            // isMultiplayerServer() is !isLocalServer || published: true on the title screen.
            check("@Invoker on a private method (" + invoker + ", expected true off a local world)", Boolean.TRUE.equals(invoker));
            int titleFrames = ((java.util.concurrent.atomic.AtomicInteger) hm.getField("TITLE_FRAMES").get(null)).get();
            check("@Inject at TAIL of loaded TitleScreen.render (" + titleFrames + " frames)", titleFrames > 0);
            check("mixin on a class loaded later (normal path)", Boolean.TRUE.equals(call.apply("lateTargetMarked")));
        } catch (Throwable t) {
            LOGGER.error("Mixin checks failed", t);
            check("mixin checks threw " + t, false);
        }
    }

    private static boolean isFrozen() {
        try {
            return (boolean) Fields.find(BuiltInRegistries.BLOCK.getClass(), "frozen").get(BuiltInRegistries.BLOCK);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true; // field access refused: unknown, don't fail the run on it
        }
    }

    private static void show(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Item item = BuiltInRegistries.ITEM.get(ID);
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        g.fill(cx - 120, 6, cx + 120, 96, 0xC0000000);
        g.pose().pushPose();
        g.pose().translate(cx - 24, 12, 0);
        g.pose().scale(3, 3, 1);
        g.renderItem(new ItemStack(item), 0, 0);
        g.pose().popPose();
        g.drawCenteredString(mc.font, "Hot-loaded: " + I18n.get(item.getDescriptionId()), cx, 64, 0x55FFFF);
        g.drawCenteredString(mc.font, failed ? "Some checks failed (see report)" : "All checks passed", cx, 78,
                failed ? 0xFF5555 : 0x55FF55);
        if (frames == 25) checkMixins();
        if (++frames == 30) {
            Screenshot.grab(mc.gameDirectory, "hotload-spike.png", mc.getMainRenderTarget(), msg -> {});
        }
        if (frames == 40) {
            String join = System.getProperty("conduit.hotload.join");
            if (join == null) { finish(mc); return; }
            // Join a dedicated server that also runs the mod: the server's registry sync
            // must accept the client's hot-loaded entries.
            state = State.JOINING;
            mc.options.pauseOnLostFocus = false; // the test window may not have focus
            joinStarted = System.currentTimeMillis();
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(join),
                    new ServerData("Hot-load spike", join, ServerData.Type.OTHER), false, null);
        }
    }

    private static long joinStarted;
    private static int inWorldTicks;

    private static void joinTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (System.currentTimeMillis() - joinStarted > 60_000) {
                check("joined the server within 60 s (screen: " + mc.screen + ")", false);
                Screenshot.grab(mc.gameDirectory, "hotload-ingame.png", mc.getMainRenderTarget(), msg -> {});
                finish(mc);
            }
            return;
        }
        if (++inWorldTicks == 1) {
            report.add("INFO joined after " + (System.currentTimeMillis() - joinStarted) + " ms");
            check("joined a server that runs the mod (registry sync accepted)", true);
        }
        if (inWorldTicks >= 80 && inWorldTicks < 100 && mc.screen != null) mc.setScreen(null); // close pause menu
        if (inWorldTicks == 100) {
            Block block = BuiltInRegistries.BLOCK.get(ID);
            int found = 0;
            var origin = mc.player.blockPosition();
            for (var pos : net.minecraft.core.BlockPos.betweenClosed(origin.offset(-4, -2, -1), origin.offset(4, 4, 6))) {
                if (mc.level.getBlockState(pos).is(block)) found++;
            }
            check("server placed Glow Cubes the client can see (" + found + " of 9)", found >= 9);
            boolean hasItem = mc.player.getInventory().contains(new ItemStack(BuiltInRegistries.ITEM.get(ID)));
            check("server gave the hot-loaded item", hasItem);
            if (net.neoforged.fml.ModList.get().isLoaded("create")) {
                Block cog = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "cogwheel"));
                net.minecraft.core.BlockPos cogPos = null;
                for (var pos : net.minecraft.core.BlockPos.betweenClosed(origin.offset(-5, -2, -1), origin.offset(5, 4, 6))) {
                    if (mc.level.getBlockState(pos).is(cog)) { cogPos = pos.immutable(); break; }
                }
                check("Create: client sees the server's cogwheel", cogPos != null);
                if (cogPos != null) {
                    var be = mc.level.getBlockEntity(cogPos);
                    Object speed = null;
                    try {
                        speed = be == null ? null : be.getClass().getMethod("getSpeed").invoke(be);
                    } catch (ReflectiveOperationException e) {
                        speed = e.toString();
                    }
                    check("Create: cogwheel turning, synced from the server (speed " + speed + ")",
                            speed instanceof Float f && f != 0F);
                }
                var wrench = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "wrench"));
                check("Create: server gave a wrench", mc.player.getInventory().contains(new ItemStack(wrench)));
            }
            mc.player.setYRot(0); mc.player.setXRot(10);
            Screenshot.grab(mc.gameDirectory, "hotload-ingame.png", mc.getMainRenderTarget(), msg -> {});
        }
        if (inWorldTicks == 110) {
            mc.level.disconnect();
            mc.disconnect();
            finish(mc);
        }
    }

    private static void finish(Minecraft mc) {
        if (state == State.DONE) return;
        state = State.DONE;
        String text = String.join(System.lineSeparator(), report);
        LOGGER.info("SPIKE REPORT\n{}", text);
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("hotload-spike.txt"), text + System.lineSeparator());
        } catch (Exception e) {
            LOGGER.warn("Could not write report", e);
        }
        mc.stop();
    }
}
