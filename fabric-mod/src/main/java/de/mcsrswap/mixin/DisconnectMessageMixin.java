package de.mcsrswap.mixin;

import de.mcsrswap.SwapMod;
import java.util.UUID;
import net.minecraft.network.MessageType;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses the "left the game" chat broadcast in onDisconnected. */
@Mixin(ServerPlayNetworkHandler.class)
public class DisconnectMessageMixin {

    @Shadow public net.minecraft.server.network.ServerPlayerEntity player;

    /** Capture hotbar preference and last-player UUID before vanilla saves and removes. */
    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void onDisconnectHead(Text reason, CallbackInfo ci) {
        SwapMod.onPlayerDisconnect(player);
    }

    @Redirect(
            method = "onDisconnected",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/PlayerManager;broadcastChatMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/MessageType;Ljava/util/UUID;)V"))
    private void suppressDisconnectMessage(
            PlayerManager manager, Text message, MessageType type, UUID sender) {
        // suppress – no disconnect message on game servers
    }
}
