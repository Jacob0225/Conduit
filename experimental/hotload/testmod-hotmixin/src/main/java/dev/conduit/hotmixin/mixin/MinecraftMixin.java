package dev.conduit.hotmixin.mixin;

import dev.conduit.hotmixin.HotMixin;
import dev.conduit.hotmixin.TickCounter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/** Injector, added instance + static fields, added interface + methods, and a lambda. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements TickCounter {
    @Unique private int hotmixin$tickCount;
    @Unique private static final AtomicInteger hotmixin$staticTickCount = new AtomicInteger();

    @Inject(method = "tick", at = @At("HEAD"))
    private void hotmixin$onTick(CallbackInfo ci) {
        hotmixin$tickCount++;
        hotmixin$staticTickCount.incrementAndGet();
        Runnable lambda = () -> HotMixin.LAMBDA_RUNS.incrementAndGet();
        lambda.run();
    }

    @Override
    public int hotmixin$ticks() {
        return hotmixin$tickCount;
    }

    @Override
    public int hotmixin$staticTicks() {
        return hotmixin$staticTickCount.get();
    }
}
