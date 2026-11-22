package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import space.sableorbit.Attitude;
import space.sableorbit.GravityFrame;

@Mixin(LivingEntity.class)
abstract class DeckTravelMixin {
    @WrapOperation(method="jumpFromGround", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"))
    private void jumpFromDeck(LivingEntity entity, double x, double power, double z, Operation<Void> original) {
        var frame = GravityFrame.resolve(entity);
        if (frame.deck() != null) {
            var up = frame.up();
            var velocity = entity.getDeltaMovement();
            var jump = velocity.add(up.scale(power - velocity.dot(up)));
            original.call(entity, jump.x, jump.y, jump.z);
        } else original.call(entity, x, power, z);
    }

    @ModifyArg(method="jumpFromGround", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private Vec3 sprintAlongDeck(Vec3 impulse) {
        var entity = (LivingEntity)(Object)this;
        if (GravityFrame.resolve(entity).deck() == null) return impulse;
        var forward = Attitude.body(entity).transform(0, 0, 1, new org.joml.Vector3f());
        return new Vec3(forward.x, forward.y, forward.z).scale(impulse.length());
    }

    @ModifyExpressionValue(method="travel", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/LivingEntity;handleRelativeFrictionAndCalculateMovement(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 localGravityAxes(Vec3 velocity, @Share("gravityAxes") LocalRef<Quaternionf> axes) {
        var entity = (LivingEntity)(Object)this;
        var frame = GravityFrame.resolve(entity);
        if (frame.deck() == null) return velocity;
        var up = frame.up();
        double normalSpeed = velocity.dot(up);
        if (entity.verticalCollisionBelow && normalSpeed < 0) velocity = velocity.subtract(up.scale(normalSpeed));
        // Static friction balances downhill gravity while the player has solid support.
        // Once airborne the planet pulls along world Y again, even while the body stays tilted.
        boolean supportedPlayer = entity instanceof net.minecraft.world.entity.player.Player && entity.onGround()
            && dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(entity) == frame.deck() && normalSpeed <= 1e-5;
        if (!frame.artificial() && !supportedPlayer) return velocity;
        var rotation = Attitude.deckBasis(frame);
        axes.set(rotation);
        var local = rotation.transformInverse(new Vector3d(velocity.x, velocity.y, velocity.z));
        return new Vec3(local.x, local.y, local.z);
    }

    @WrapOperation(method="travel", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"),
        slice=@Slice(from=@At(value="INVOKE", target="Lnet/minecraft/world/entity/LivingEntity;handleRelativeFrictionAndCalculateMovement(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;")))
    private void worldGravityResult(LivingEntity entity, double x, double y, double z, Operation<Void> original,
                                    @Share("gravityAxes") LocalRef<Quaternionf> axes) {
        if (axes.get() == null) {
            original.call(entity, x, y, z);
            return;
        }
        var world = axes.get().transform(new Vector3d(x, y, z));
        original.call(entity, world.x, world.y, world.z);
    }
}
