package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.*;

@Mixin(LocalPlayer.class)
abstract class EvaSendMixin {
    @WrapOperation(method="sendPosition",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void sendInFrame(ClientPacketListener connection, Packet<?> packet, Operation<Void> send) {
        var player = (LocalPlayer)(Object)this;
        var hull = DeckFrame.movingDepartureHull(player);
        // With a stationary release point the displacement is already in world axes.
        // A brief brush with a stopped hull needs no timed departure on the server.
        if (hull != null && packet instanceof ServerboundMovePlayerPacket move && move.hasPosition() && !player.isPassenger() && !player.isSpectator()
                && Sable.HELPER.getTrackingSubLevel(player)==null && connection.hasChannel(EvaMove.TYPE)) {
            var displacement = player.position().subtract(DeckFrame.departurePosition(player));
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer((net.minecraft.client.multiplayer.ClientLevel)player.level());
            double displayTime = container.getInterpolation().mostRecentInterpolationTick;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new EvaMove(player.level().dimension(),hull,
                DeckFrame.departureTime(player), displayTime, displacement,move.getYRot(player.getYRot()),move.getXRot(player.getXRot()),move.isOnGround()));
        } else send.call(connection,packet);
    }
}
