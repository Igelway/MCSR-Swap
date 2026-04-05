package de.mcsrswap.mixin;

import de.mcsrswap.SwapMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects when a living player steps into the End exit portal. More precise than tick-based
 * world-change detection: - fires exactly when the portal is entered (not 1 tick later) - cannot be
 * triggered by death/respawn - cannot be triggered by the Overworld entrance portal
 */
@Mixin(EndPortalBlock.class)
public class EndPortalMixin {

    @Inject(method = "onEntityCollision", at = @At("HEAD"))
    private void onPlayerEnterEndPortal(
            BlockState state, World world, BlockPos pos, Entity entity, CallbackInfo ci) {
        // Only care about survival players in the End on the server side
        if (!(entity instanceof ServerPlayerEntity)) return;
        if (!(world instanceof ServerWorld)) return;
        if (world.getRegistryKey() != World.END) return;

        ServerPlayerEntity player = (ServerPlayerEntity) entity;
        if (player.getHealth() <= 0.0f) return; // dead player stepping on leftover portal block

        SwapMod.onEndPortalEntered(player);
    }
}
