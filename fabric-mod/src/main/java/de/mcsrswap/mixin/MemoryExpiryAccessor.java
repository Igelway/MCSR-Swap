package de.mcsrswap.mixin;

import net.minecraft.entity.ai.brain.Memory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Memory.expiry (remaining ticks as long) for TTL-preserving anger transfer. */
@Mixin(Memory.class)
public interface MemoryExpiryAccessor {
    @Accessor("expiry")
    long getExpiry();
}
