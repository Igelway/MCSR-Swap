package de.mcsrswap.mixin;

import java.util.Map;
import java.util.Optional;
import net.minecraft.entity.ai.brain.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Brain.memories so we can read raw Memory objects (including TTL). */
@Mixin(Brain.class)
public interface BrainMemoriesAccessor {
    @Accessor("memories")
    Map<?, Optional<?>> getMemories();
}
