package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.client.ConduitLock;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Conduit is installing mods, suppress incoming damage and health
 * update packets so the player can't die while frozen in the void.
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleHurtAnimation", at = @At("HEAD"), cancellable = true)
    private void conduit_suppressHurt(ClientboundHurtAnimationPacket packet, CallbackInfo ci) {
        if (ConduitLock.isLocked()) ci.cancel();
    }

    @Inject(method = "handleSetHealth", at = @At("HEAD"), cancellable = true)
    private void conduit_suppressHealthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        if (ConduitLock.isLocked()) ci.cancel();
    }
}
