package de.mcsrswap.mixin;

import net.minecraft.entity.ai.brain.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Optional;

/** Exposes Brain.memories so we can read raw Memory objects (including TTL). */
@Mixin(Brain.class)
public interface BrainMemoriesAccessor {
    @Accessor("memories")
    Map<?, Optional<?>> getMemories();
}
