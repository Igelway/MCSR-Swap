package de.mcsrswap.mixin;

import net.minecraft.network.packet.c2s.play.ClientSettingsC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientSettingsC2SPacket.class)
public interface ClientSettingsAccessor {
    @Accessor("language")
    String getLanguage();
}
