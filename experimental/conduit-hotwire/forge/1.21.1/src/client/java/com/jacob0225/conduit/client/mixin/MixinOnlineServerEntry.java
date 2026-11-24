package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.network.ConduitStatusMarker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Minecraft Conduit item beside servers advertising Conduit support. */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class MixinOnlineServerEntry {
    private static final ResourceLocation CONDUIT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("conduit", "textures/gui/conduit.png");

    @Shadow @Final private ServerData serverData;

    @Inject(method = "render", at = @At("TAIL"))
    private void conduit$renderBadge(GuiGraphics graphics, int index, int top, int left,
                                     int width, int height, int mouseX, int mouseY,
                                     boolean hovered, float delta, CallbackInfo callback) {
        if (!ConduitStatusMarker.isPresent(serverData.motd)) return;
        int x = left + width - 18;
        int y = top + height - 18;
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0x99000000);
        graphics.blit(CONDUIT_TEXTURE, x, y, 16, 16,
                0.0F, 0.0F, 100, 110, 100, 110);
    }
}
