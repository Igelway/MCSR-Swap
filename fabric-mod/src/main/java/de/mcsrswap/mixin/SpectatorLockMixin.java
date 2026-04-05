package de.mcsrswap.mixin;

import de.mcsrswap.SwapMod;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class SpectatorLockMixin {

    /**
     * Prevents a watching player from escaping their locked spectator camera. In vanilla, pressing
     * Shift calls setCameraEntity(self) which releases the lock. We cancel that call as long as the
     * player is in SwapMod's spectatorCameras map. The lock is only removed by Velocity sending a
     * "prepare_return" or "become_spectator" messages, which go through SwapMod directly and bypass
     * this injection.
     */
    @Inject(method = "setCameraEntity", at = @At("HEAD"), cancellable = true)
    private void preventSpectatorEscape(Entity entity, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        // Only block when the game tries to reset the camera back to the player themselves
        // (= the escape action). Allow all other setCameraEntity calls (our initial lock, etc.).
        if (entity == (Object) self && SwapMod.isSpectatorLocked(self.getUuid())) {
            ci.cancel();
        }
    }
}
