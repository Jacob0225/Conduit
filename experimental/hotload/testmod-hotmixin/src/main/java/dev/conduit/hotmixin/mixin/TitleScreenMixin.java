package dev.conduit.hotmixin.mixin;

import dev.conduit.hotmixin.HotMixin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** @Inject at TAIL of a loaded screen's render, calling into the hot-loaded mod. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void hotmixin$countFrame(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        HotMixin.TITLE_FRAMES.incrementAndGet();
    }
}
