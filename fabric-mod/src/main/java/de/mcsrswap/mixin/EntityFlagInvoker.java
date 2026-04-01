package de.mcsrswap.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Entity.setFlag(int, boolean) so we can set elytra fall-flying (flag index 7). */
@Mixin(Entity.class)
public interface EntityFlagInvoker {
    @Invoker("setFlag")
    void invokeSetFlag(int index, boolean value);
}
