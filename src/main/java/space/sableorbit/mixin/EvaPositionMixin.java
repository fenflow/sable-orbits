package space.sableorbit.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.DeckFrame;
import space.sableorbit.Spacewalk;

@Mixin(ClientPacketListener.class)
abstract class EvaPositionMixin {
    @Inject(method="handleMovePlayer",at=@At("RETURN"))
    private void clearDeparture(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (Spacewalk.rotationOnly(packet.getRelativeArguments(), packet.getX(), packet.getY(), packet.getZ())) return;
        DeckFrame.corrected(Minecraft.getInstance().player);
    }
}
