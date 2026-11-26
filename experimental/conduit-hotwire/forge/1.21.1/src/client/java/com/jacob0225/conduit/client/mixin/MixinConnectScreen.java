package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.client.network.ConduitJoinInterceptor;
import com.jacob0225.conduit.client.network.ConduitPreflight;
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
 * Remembers each vanilla join attempt so the login-query receiver can associate
 * the advertised Conduit port with the address and UI that initiated it.
 *
 * <p>This is what eliminates the registry-mismatch error: by the time the
 * vanilla connect runs, the client has already installed any mods the server's
 * manifest requires, so the configuration-phase registry sync will decode fine.
 *
 * <p>Both the server list "Join" button and Direct Connect funnel through
 * {@code startConnecting}, so one intercept covers every join path.
 *
 * <p>Before that, {@link ConduitPreflight} checks the server's manifest, since
 * mods with required network channels end the join before the in-login check.
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

        if (ConduitPreflight.intercept(parent, mc, address, serverData)) {
            ci.cancel(); // checking the server's mods first; it joins (or shows setup) when done
            return;
        }

        ConduitJoinInterceptor.rememberConnection(parent, address, serverData);
    }
}
