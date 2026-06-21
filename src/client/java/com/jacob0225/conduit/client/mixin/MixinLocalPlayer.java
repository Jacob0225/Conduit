package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.client.ConduitLock;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Conduit is installing mods, suppress the client-side movement tick
 * so the player stays frozen in place and no movement packets are sent.
 */
@Mixin(LocalPlayer.class)
public class MixinLocalPlayer {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void conduit_freezeMovement(CallbackInfo ci) {
        if (ConduitLock.isLocked()) {
            ci.cancel();
        }
    }
}
