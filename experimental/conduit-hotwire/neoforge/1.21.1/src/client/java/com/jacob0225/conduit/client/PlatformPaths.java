package com.jacob0225.conduit.client;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class PlatformPaths {
    private PlatformPaths() {}
    public static Path gameDir() { return FMLPaths.GAMEDIR.get(); }
    public static Path configDir() { return FMLPaths.CONFIGDIR.get(); }
    /** The real game version (getLaunchedVersion is the launcher profile name, e.g. "neoforge-21.1.x"). */
    public static String minecraftVersion() { return net.minecraft.SharedConstants.getCurrentVersion().getName(); }
}
