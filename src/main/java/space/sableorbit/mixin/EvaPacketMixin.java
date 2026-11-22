package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.Spacewalk;
import space.sableorbit.EvaMove;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class EvaPacketMixin implements EvaMove.Connection, space.sableorbit.BodyClearance.Connection {
    @Shadow public ServerPlayer player;
    @Shadow private double firstGoodX, firstGoodY, firstGoodZ;
    @Shadow private Vec3 awaitingPositionFromClient;
    @Unique private final EvaMove.Recovery sableorbits$recovery = new EvaMove.Recovery();
    @Unique private Vec3 sableorbits$acceptedFeet;

    public Vec3 sableorbits$acceptedFeet() { return sableorbits$acceptedFeet; }

    // Vanilla restores this accepted position immediately after doTick. Its temporary
    // gravity step can put the feet inside a tilted floor during the stance check.
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="tick", at=@At(value="INVOKE",
        target="Lnet/minecraft/server/level/ServerPlayer;doTick()V"))
    private void acceptedStanceOrigin(ServerPlayer pilot, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> tick) {
        sableorbits$acceptedFeet = pilot.position();
        try { tick.call(pilot); }
        finally { sableorbits$acceptedFeet = null; }
    }

    public void sableorbits$rejectDeparture(EvaMove move) {
        if (awaitingPositionFromClient == null && sableorbits$recovery.needsCorrection(move, player.tickCount))
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @ModifyExpressionValue(method="handleMovePlayer", at=@At(value="INVOKE", target="Lnet/minecraft/world/phys/Vec3;lengthSqr()D",ordinal=0))
    private double checkRelativeSpeed(double expected, ServerboundMovePlayerPacket packet) {
        if (!(packet instanceof EvaMove.Projected move)) return expected;
        var movement = new Vec3(packet.getX(player.getX())-firstGoodX,packet.getY(player.getY())-firstGoodY,packet.getZ(player.getZ())-firstGoodZ);
        // Vanilla subtracts this value from world displacement squared. Substitute the
        // distance travelled relative to the inertial departure trajectory while retaining vanilla's speed and packet budget.
        return expected + movement.lengthSqr() - move.relativeDistanceSquared;
    }

    @Inject(method="handleMovePlayer",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;updateAwaitingTeleport()Z"),cancellable=true)
    private void waitForBoarding(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (space.sableorbit.Boarding.pending(player)) ci.cancel();
    }

    @Inject(method="teleport(DDDFFLjava/util/Set;)V", at=@At("HEAD"))
    private void forgetDeparture(double x, double y, double z, float yaw, float pitch,
            java.util.Set<net.minecraft.world.entity.RelativeMovement> relative, CallbackInfo ci) {
        if (Spacewalk.rotationOnly(relative, x-player.getX(), y-player.getY(), z-player.getZ())) return;
        sableorbits$recovery.teleported(player.tickCount);
        Spacewalk.forget(player);
    }

    @Inject(method="handleMovePlayer",at=@At("RETURN"))
    private void rememberDeck(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!packet.hasPosition() || awaitingPositionFromClient != null) return;
        sableorbits$recovery.accepted();
        if (!(packet instanceof EvaMove.Projected)) Spacewalk.observeContact(player);
    }
}
