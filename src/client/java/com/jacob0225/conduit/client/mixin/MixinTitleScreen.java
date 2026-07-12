package com.jacob0225.conduit.client.mixin;

import com.jacob0225.conduit.client.ui.ConduitSettingsScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {

    @Unique
    private Button conduit$settingsButton;

    protected MixinTitleScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void conduit$addSettingsButton(CallbackInfo ci) {
        int x = this.width - 34;
        int y = 8;
        conduit$settingsButton = Button.builder(
                Component.literal("\u2699"),
                btn -> this.minecraft.setScreen(new ConduitSettingsScreen(this))
        ).bounds(x, y, 24, 20).build();
        addRenderableWidget(conduit$settingsButton);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void conduit$drawSettingsLabel(GuiGraphicsExtractor g, int mx, int my, float delta,
                                           CallbackInfo ci) {
        if (conduit$settingsButton == null || !conduit$settingsButton.visible) return;
        String label = "CONDUIT";
        int cx = conduit$settingsButton.getX() + conduit$settingsButton.getWidth() / 2;
        g.text(font, label, cx - font.width(label) / 2, conduit$settingsButton.getY() + 23,
                0xFFFFFFFF, true);
    }
}
