package space.sableorbit.mixin;

import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.FlightVelocity;

@Mixin(ServerEntity.class)
abstract class FlightVelocityMixin {
    @Shadow @Final private Entity entity;
    @Shadow private Vec3 lastSentMovement;
    @Unique private Vec3 sableorbits$before;
    @Unique private boolean sableorbits$forced;

    @Inject(method="sendPairingData",at=@At("TAIL"))
    private void initial(ServerPlayer player,PacketAndPayloadAcceptor<ClientGamePacketListener> packets,CallbackInfo ci) {
        if (FlightVelocity.needed(entity)) packets.accept(new FlightVelocity(entity.getId(),entity.getDeltaMovement()));
    }
    @Inject(method="sendChanges",at=@At("HEAD"))
    private void before(CallbackInfo ci) {
        sableorbits$before=lastSentMovement;
        sableorbits$forced=entity.hurtMarked;
    }
    @Inject(method="sendChanges",at=@At("RETURN"))
    private void after(CallbackInfo ci) {
        if ((sableorbits$forced || sableorbits$before!=lastSentMovement) && FlightVelocity.needed(entity))
            PacketDistributor.sendToPlayersTrackingEntity(entity,new FlightVelocity(entity.getId(),entity.getDeltaMovement()));
    }
}
