package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.network.ConduitStatusMarker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Minecraft Conduit item beside servers advertising Conduit support. */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class MixinOnlineServerEntry {
    private static final Identifier CONDUIT_TEXTURE =
            Identifier.fromNamespaceAndPath("conduit", "textures/gui/conduit.png");

    @Shadow @Final private ServerData serverData;

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void conduit$renderBadge(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     boolean hovered, float delta, CallbackInfo callback) {
        if (!ConduitStatusMarker.isPresent(serverData.motd)) return;
        ServerSelectionList.OnlineServerEntry entry =
                (ServerSelectionList.OnlineServerEntry) (Object) this;
        int x = entry.getContentRight() - 18;
        int y = entry.getContentBottom() - 18;
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0x99000000);
        graphics.blit(CONDUIT_TEXTURE, x, y, x + 16, y + 16,
                0.0F, 1.0F, 0.0F, 1.0F);
    }
}
