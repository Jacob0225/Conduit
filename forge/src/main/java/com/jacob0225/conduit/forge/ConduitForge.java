package com.jacob0225.conduit.forge;

import com.jacob0225.conduit.server.ConduitServerRuntime;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge server lifecycle adapter. The shared runtime owns all Conduit logic. */
@Mod(ConduitForge.MOD_ID)
public final class ConduitForge {
    public static final String MOD_ID = "conduit";
    private final ConduitServerRuntime runtime = new ConduitServerRuntime();

    public ConduitForge() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        runtime.start(event.getServer().getServerDirectory(), event.getServer().getServerVersion());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        runtime.stop();
    }
}
