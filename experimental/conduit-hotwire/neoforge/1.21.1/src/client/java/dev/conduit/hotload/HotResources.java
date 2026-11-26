package dev.conduit.hotload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.locating.IModFile;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Client half: adds the new mod's assets to NeoForge's "Mod Resources" pack and
 * reloads resources, which rebuilds language files, textures and baked models
 * (the model bakery walks the block registry, so new blocks get models).
 */
public final class HotResources {

    private HotResources() {}

    public static CompletableFuture<Void> addAndReload(java.util.List<ModFile> modFiles) {
        Map<IModFile, Pack.ResourcesSupplier> packs = Fields.getStatic(ResourcePackLoader.class, "modResourcePacks");
        for (ModFile modFile : modFiles) packs.put(modFile, ResourcePackLoader.createPackForMod(modFile.getModFileInfo()));

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
        mc.getResourcePackRepository().reload(); // re-runs the pack finders, so "mod_resources" gains the jar
        Fields.setStatic(CreativeModeTabs.class, "CACHED_PARAMETERS", null); // creative tabs rebuild on next open
        return mc.reloadResourcePacks();
    }
}
