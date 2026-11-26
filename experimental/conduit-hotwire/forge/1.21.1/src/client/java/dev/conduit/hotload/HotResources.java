package dev.conduit.hotload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;

import java.util.concurrent.CompletableFuture;

/**
 * Client half: Forge builds the mod resource packs from {@code ModList} every time packs are
 * reloaded, so once the new mods are listed a reload picks up their assets and rebuilds
 * language files, textures and baked models (the model bakery walks the block registry).
 */
public final class HotResources {

    private HotResources() {}

    public static CompletableFuture<Void> addAndReload(java.util.List<ModFile> modFiles) {
        Minecraft mc = Minecraft.getInstance();
        // ItemRenderer maps every item to its model location once, at startup. Map the new ones;
        // the reload below then bakes them and rebuildCache() fills in the models.
        var shaper = mc.getItemRenderer().getItemModelShaper();
        for (String namespace : modFiles.stream().flatMap(f -> f.getModInfos().stream()).map(i -> i.getModId()).toList()) {
            for (var entry : BuiltInRegistries.ITEM.entrySet()) {
                ResourceLocation id = entry.getKey().location();
                if (id.getNamespace().equals(namespace)) {
                    shaper.register(entry.getValue(), ModelResourceLocation.inventory(id));
                }
            }
        }
        mc.getResourcePackRepository().reload(); // re-runs the pack finders, so the new mods' packs appear
        Fields.setStatic(CreativeModeTabs.class, "CACHED_PARAMETERS", null); // creative tabs rebuild on next open
        return mc.reloadResourcePacks();
    }
}
