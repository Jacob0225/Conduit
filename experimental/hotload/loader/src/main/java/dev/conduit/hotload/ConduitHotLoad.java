package dev.conduit.hotload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod("conduithotload")
public final class ConduitHotLoad {
    public ConduitHotLoad() {
        if (FMLEnvironment.dist == Dist.CLIENT && System.getProperty(SpikeHarness.PROPERTY) != null) {
            SpikeHarness.install();
        }
    }
}
