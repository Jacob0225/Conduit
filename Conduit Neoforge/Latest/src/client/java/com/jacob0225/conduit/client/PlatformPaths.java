package com.jacob0225.conduit.client;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class PlatformPaths {
    private PlatformPaths() {}
    public static Path gameDir() { return FMLPaths.GAMEDIR.get(); }
    public static Path configDir() { return FMLPaths.CONFIGDIR.get(); }
    public static String minecraftVersion() { return Minecraft.getInstance().getLaunchedVersion(); }
}
