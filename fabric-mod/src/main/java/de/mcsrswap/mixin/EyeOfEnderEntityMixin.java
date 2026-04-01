package de.mcsrswap.mixin;

import de.mcsrswap.ModConfig;
import net.minecraft.entity.EyeOfEnderEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Extends the total lifetime of a thrown Eye of Ender.
 * Vanilla: 80 ticks total lifetime; hover phase is ~40 ticks (~2 s).
 * Configured via the Velocity config (eyeHoverTicks); falls back to
 * config/worldswap/config.properties if no Velocity message has been received.
 */
@Mixin(EyeOfEnderEntity.class)
public class EyeOfEnderEntityMixin {

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 80))
    private int extendHoverDuration(int original) {
        return ModConfig.eyeHoverTicks;
    }
}
