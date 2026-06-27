package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts every join attempt at its single chokepoint —
 * {@link ConnectScreen#startConnecting} — and runs Conduit's pre-join manifest
 * check <b>before</b> the game connection is opened.
 *
 * <p>This is what eliminates the registry-mismatch error: by the time the
 * vanilla connect runs, the client has already installed any mods the server's
 * manifest requires, so the configuration-phase registry sync will decode fine.
 *
 * <p>Both the server list "Join" button and Direct Connect funnel through
 * {@code startConnecting}, so one intercept covers every join path.
 *
 * <p><b>Recursion.</b> After a successful install, {@link ModReviewScreen}
 * re-issues a join (which re-enters this mixin). To avoid re-checking and
 * looping, {@link ConduitJoinInterceptor#bypassNextCheck()} is set first; this
 * mixin consumes it via {@link ConduitJoinInterceptor#shouldBypass()}.
 */
@Mixin(ConnectScreen.class)
public class MixinConnectScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger("Conduit/Mixin");

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void conduit$interceptJoin(
            Screen parent,
            Minecraft mc,
            ServerAddress address,
            ServerData serverData,
            boolean direct,
            TransferState transferState,
            CallbackInfo ci
    ) {
        LOGGER.info("⟶ startConnecting fired: {}:{} (direct={}, serverData={})",
                address.getHost(), address.getPort(), direct,
                serverData == null ? "null" : "present");

        // Bypass the check for the re-join that follows a successful install.
        if (ConduitJoinInterceptor.shouldBypass()) {
            LOGGER.info("⟶ bypass flag set — letting vanilla connect proceed (no manifest check)");
            return; // let vanilla proceed
        }

        // Cancel the vanilla connect; we'll re-issue it once mods are verified.
        LOGGER.info("⟶ cancelling vanilla connect, handing off to ConduitJoinInterceptor");
        ci.cancel();

        // Build the "real connect" as a Runnable. When the manifest check passes
        // (mods already present, or install completes), this is invoked to do
        // the actual join — bypassing the check so it doesn't loop.
        Runnable proceed = () -> {
            ConduitJoinInterceptor.bypassNextCheck();
            // Re-enter the real startConnecting. The bypass flag we just set
            // makes this second call skip the intercept and run for real.
            ConnectScreen.startConnecting(parent, mc, address, serverData, direct, transferState);
        };

        ConduitJoinInterceptor.intercept(parent, address, serverData, proceed);
    }
}
