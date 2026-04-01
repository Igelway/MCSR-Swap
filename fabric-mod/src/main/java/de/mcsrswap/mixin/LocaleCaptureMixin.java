package de.mcsrswap.mixin;

import de.mcsrswap.Lang;
import net.minecraft.network.packet.c2s.play.ClientSettingsC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the client's locale from ClientSettingsC2SPacket for per-player language selection. */
@Mixin(ServerPlayNetworkHandler.class)
public class LocaleCaptureMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onClientSettings", at = @At("HEAD"))
    private void captureLocale(ClientSettingsC2SPacket packet, CallbackInfo ci) {
        String locale = ((ClientSettingsAccessor) packet).getLanguage();
        Lang.setLocale(player.getUuid(), locale);
    }
}
