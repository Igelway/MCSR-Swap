package de.mcsrswap.mixin;

import java.util.UUID;
import net.minecraft.network.MessageType;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Suppresses the "left the game" chat broadcast in onDisconnected. */
@Mixin(ServerPlayNetworkHandler.class)
public class DisconnectMessageMixin {

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
