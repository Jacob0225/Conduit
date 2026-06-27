package com.jacob0225.conduit.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@code serverData} field on the common packet-listener base so
 * Conduit can read the address the user typed before disconnecting to install
 * mods. Without this, the client cannot auto-reconnect after install because
 * the original IP is lost when the connection is torn down.
 *
 * <p>This is a {@link org.spongepowered.asm.mixin.gen.Accessor @Accessor} mixin
 * — it only reads an existing field; it does not change behavior.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface MixinClientConfigurationConnectionAccess {

    @Accessor("serverData")
    ServerData conduit_getServerData();
}
