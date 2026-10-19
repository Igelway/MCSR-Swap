package de.mcsrswap.mixin;

import de.mcsrswap.SwapMod;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the pending spectator switch after vanilla has sent the world information. This replaces
 * the old tick-countdown and has no fixed delay.
 */
@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "sendWorldInfo", at = @At("TAIL"))
    private void afterSendWorldInfo(ServerPlayerEntity player, ServerWorld world, CallbackInfo ci) {
        SwapMod.onWorldInfoSent(player);
    }
}
