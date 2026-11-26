package dev.conduit.hotload;

import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;

import java.util.List;

/** FML's normal Java mod container, with its protected construction step exposed. */
final class HotContainer extends FMLModContainer {
    HotContainer(IModInfo info, List<String> entrypoints, ModFileScanData scan, ModuleLayer layer) {
        super(info, entrypoints, scan, layer);
    }

    void construct() {
        constructMod();
    }
}
