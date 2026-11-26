package dev.conduit.hotmixin.mixin;

import net.minecraft.client.gui.components.SplashRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** @ModifyArg on a loaded class: visible as "Mixin'd!" in the splash text. */
@Mixin(SplashRenderer.class)
public abstract class SplashRendererMixin {
    @ModifyArg(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"),
            index = 1)
    private String hotmixin$splash(String text) {
        return "Mixin'd! " + text;
    }
}
