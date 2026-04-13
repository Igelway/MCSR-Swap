package de.mcsrswap.mixin;

import de.mcsrswap.ModConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import net.minecraft.world.WorldSaveHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects player data file I/O to a fixed "slot UUID" .dat file instead of each player's own
 * UUID. This means every player on this server shares one save file, inheriting the previous
 * player's full state (inventory, XP, effects, position, ender chest, vehicle, etc.) without any
 * manual serialisation. Mob entities stay angry at the slot UUID between rotations.
 *
 * <p>Save is NOT redirected for spectator players so they never overwrite the slot data.
 */
@Mixin(WorldSaveHandler.class)
public abstract class PlayerDataMixin {

    @Redirect(
            method = "loadPlayerData",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/entity/player/PlayerEntity;getUuidAsString()Ljava/lang/String;"))
    private String redirectLoadUuid(PlayerEntity player) {
        return ModConfig.slotUuid != null
                ? ModConfig.slotUuid.toString()
                : player.getUuidAsString();
    }

    @Redirect(
            method = "savePlayerData",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/entity/player/PlayerEntity;getUuidAsString()Ljava/lang/String;"))
    private String redirectSaveUuid(PlayerEntity player) {
        if (ModConfig.slotUuid == null) return player.getUuidAsString();
        // Spectators (watchers) must never overwrite the slot data.
        if (player instanceof ServerPlayerEntity) {
            ServerPlayerEntity spe = (ServerPlayerEntity) player;
            if (spe.interactionManager.getGameMode() == GameMode.SPECTATOR) {
                return player.getUuidAsString();
            }
        }
        return ModConfig.slotUuid.toString();
    }
}
