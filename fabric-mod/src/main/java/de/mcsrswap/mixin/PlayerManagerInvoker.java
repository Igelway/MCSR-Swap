package de.mcsrswap.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the protected {@code PlayerManager.savePlayerData} method. */
@Mixin(PlayerManager.class)
public interface PlayerManagerInvoker {
    @Invoker("savePlayerData")
    void invokeSavePlayerData(ServerPlayerEntity player);
}
