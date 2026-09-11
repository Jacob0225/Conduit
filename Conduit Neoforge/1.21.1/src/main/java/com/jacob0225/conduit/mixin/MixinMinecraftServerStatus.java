package com.jacob0225.conduit.mixin;

import com.jacob0225.conduit.network.ConduitStatusMarker;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds an invisible capability marker to the normal multiplayer status ping. */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerStatus {
    @Inject(method = "buildServerStatus", at = @At("RETURN"), cancellable = true)
    private void conduit$markStatus(CallbackInfoReturnable<ServerStatus> callback) {
        ServerStatus status = callback.getReturnValue();
        callback.setReturnValue(new ServerStatus(
                ConduitStatusMarker.appendTo(status.description()),
                status.players(),
                status.version(),
                status.favicon(),
                status.enforcesSecureChat(), status.isModded()
        ));
    }
}

