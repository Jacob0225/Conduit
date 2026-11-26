package dev.conduit.hotblock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** One glowing block and its item. Used to test loading a mod into a running game. */
@Mod(HotBlock.MOD_ID)
public final class HotBlock {
    public static final String MOD_ID = "hotblock";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    public static final DeferredBlock<Block> GLOW_CUBE = BLOCKS.register("glow_cube", () -> new Block(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(1.0F)
                    .sound(SoundType.AMETHYST).lightLevel(state -> 15)));
    public static final DeferredItem<BlockItem> GLOW_CUBE_ITEM = ITEMS.register("glow_cube",
            () -> new BlockItem(GLOW_CUBE.get(), new Item.Properties()));

    public HotBlock(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) event.accept(GLOW_CUBE_ITEM);
        });
        // Server side, for the spike: build a small Glow Cube wall in front of each joining player.
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            player.getInventory().add(new ItemStack(GLOW_CUBE_ITEM.get(), 16));
            BlockPos base = player.blockPosition().south(3);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 2; dy++) {
                    player.serverLevel().setBlockAndUpdate(base.offset(dx, dy, 0), GLOW_CUBE.get().defaultBlockState());
                }
            }
            player.serverLevel().setDayTime(6000); // midday, for screenshots
            placeCreateDemo(player);
        });
    }
    /** If Create is installed: a creative motor turning a shaft and cogwheels, plus a few blocks and a wrench. */
    private static void placeCreateDemo(ServerPlayer player) {
        var blocks = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        if (!blocks.containsKey(ResourceLocation.fromNamespaceAndPath("create", "cogwheel"))) return;
        var level = player.serverLevel();
        BlockPos row = player.blockPosition().south(2).west(2);
        level.setBlockAndUpdate(row, with(create("creative_motor"), "facing", "east"));
        level.setBlockAndUpdate(row.east(1), with(create("shaft"), "axis", "x"));
        level.setBlockAndUpdate(row.east(2), with(create("cogwheel"), "axis", "x"));
        level.setBlockAndUpdate(row.east(3), with(create("large_cogwheel"), "axis", "x"));
        level.setBlockAndUpdate(row.east(4).above(), create("andesite_casing"));
        level.setBlockAndUpdate(row.east(5).above(), create("brass_block"));
        var wrench = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "wrench"));
        player.getInventory().add(new ItemStack(wrench));
    }

    private static net.minecraft.world.level.block.state.BlockState create(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", id)).defaultBlockState();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.world.level.block.state.BlockState with(
            net.minecraft.world.level.block.state.BlockState state, String property, String value) {
        net.minecraft.world.level.block.state.properties.Property p = state.getBlock().getStateDefinition().getProperty(property);
        if (p == null) return state;
        java.util.Optional v = p.getValue(value);
        return v.isPresent() ? state.setValue(p, (Comparable) v.get()) : state;
    }
}
