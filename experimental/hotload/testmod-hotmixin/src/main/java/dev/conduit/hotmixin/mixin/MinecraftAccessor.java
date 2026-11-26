package dev.conduit.hotmixin.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accessor interface: getter, setter and invoker on private members of a loaded class. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("rightClickDelay")
    int hotmixin$getRightClickDelay();

    @Accessor("rightClickDelay")
    void hotmixin$setRightClickDelay(int value);

    @Invoker("isMultiplayerServer")
    boolean hotmixin$isMultiplayerServer();
}
