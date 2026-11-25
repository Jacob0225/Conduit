package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.network.ConduitStatusMarker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the Conduit icon just left of the player count for servers that run Conduit. */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class MixinOnlineServerEntry {
    private static final Identifier CONDUIT_TEXTURE =
            Identifier.fromNamespaceAndPath("conduit", "textures/gui/conduit.png");
    private static final Component TOOLTIP = Component.literal("Conduit: required mods download when you join");
    private static final int SIZE = 9;

    @Shadow @Final private ServerData serverData;

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void conduit$renderBadge(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     boolean hovered, float delta, CallbackInfo callback) {
        if (!ConduitStatusMarker.isPresent(serverData.motd)) return;
        ServerSelectionList.OnlineServerEntry entry =
                (ServerSelectionList.OnlineServerEntry) (Object) this;

        // Same maths as vanilla: ping icon at right - 15, player count 5px left of it.
        Component count = serverData.state() == ServerData.State.INCOMPATIBLE
                ? serverData.version.copy().withStyle(ChatFormatting.RED) : serverData.status;
        int countX = entry.getContentRight() - 15 - Minecraft.getInstance().font.width(count) - 5;
        int x = countX - 4 - SIZE;
        int y = entry.getContentY();
        graphics.blit(CONDUIT_TEXTURE, x, y, x + SIZE, y + SIZE + 1, 0.0F, 1.0F, 0.0F, 1.0F);

        if (mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE + 1) {
            graphics.setTooltipForNextFrame(TOOLTIP, mouseX, mouseY);
        }
    }
}
