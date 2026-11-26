package dev.conduit.hotmixin.mixin;

import dev.conduit.hotmixin.Marker;
import net.minecraft.client.gui.screens.WinScreen;
import org.spongepowered.asm.mixin.Mixin;

/** Targets a class that is not loaded yet at hot-load time: normal Mixin path. */
@Mixin(WinScreen.class)
public abstract class LateTargetMixin implements Marker {}
