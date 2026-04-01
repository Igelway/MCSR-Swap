package de.mcsrswap.mixin;

import net.minecraft.network.MessageType;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Suppresses player join and quit chat messages on game servers.
 */
@Mixin(PlayerManager.class)
public class JoinQuitMessageMixin {

    @Redirect(
        method = "onPlayerConnect",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/PlayerManager;broadcastChatMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/MessageType;Ljava/util/UUID;)V")
    )
    private void suppressJoinMessage(PlayerManager manager, Text message, MessageType type, UUID sender) {
        // suppress – no join message on game servers
    }
}
