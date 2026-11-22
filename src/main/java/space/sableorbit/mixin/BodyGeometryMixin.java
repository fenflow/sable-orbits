package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.BodyBox;
import space.sableorbit.GravityFrame;
import space.sableorbit.Attitude;
import org.joml.Vector3d;

@Mixin(Entity.class)
abstract class BodyGeometryMixin {
    @WrapOperation(method="move", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/Entity;maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 protectTiltedEdge(Entity entity, Vec3 motion, MoverType type, Operation<Vec3> original) {
        if ((type == MoverType.SELF || type == MoverType.PLAYER) && entity instanceof net.minecraft.world.entity.player.Player player
            && player.isShiftKeyDown() && !player.isPassenger() && !player.getAbilities().flying) {
            var frame = GravityFrame.resolve(player);
            if (frame.deck() != null && frame.up().y < .999999 &&
                (player.onGround() || player.fallDistance < player.maxUpStep()))
                return space.sableorbit.DeckEdge.backOff(player, motion, frame);
        }
        return original.call(entity, motion, type);
    }

    @WrapOperation(method="move", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/level/block/Block;updateEntityAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"))
    private void deckLandingResponse(Block block, BlockGetter level, Entity entity, Operation<Void> original) {
        if (!(entity.getBoundingBox() instanceof BodyBox)) {
            original.call(block, level, entity);
            return;
        }
        var frame = GravityFrame.resolve(entity);
        if (frame.deck() == null || dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(entity) != frame.deck()) {
            original.call(block, level, entity);
            return;
        }
        // Vanilla blocks stop (and slime reflects) the vertical component at landing.
        // Give that operation the deck normal to preserve the velocity along a sloping floor.
        var axes = Attitude.deckBasis(frame);
        var velocity = entity.getDeltaMovement();
        var local = axes.transformInverse(new Vector3d(velocity.x, velocity.y, velocity.z));
        entity.setDeltaMovement(local.x, local.y, local.z);
        try {
            original.call(block, level, entity);
        } finally {
            velocity = entity.getDeltaMovement();
            var world = axes.transform(new Vector3d(velocity.x, velocity.y, velocity.z));
            entity.setDeltaMovement(world.x, world.y, world.z);
        }
    }

    @WrapOperation(method="applyGravity", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 deckGravity(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        var entity = (Entity)(Object)this;
        if (GravityFrame.nonliving(entity)) {
            var frame = GravityFrame.resolve(entity);
            if (frame.artificial()) return velocity.add(frame.up().scale(y));
        }
        return original.call(velocity, x, y, z);
    }

    @Inject(method="move", at=@At("RETURN"))
    private void stopAtDeck(MoverType type, Vec3 motion, CallbackInfo ci) {
        var entity = (Entity)(Object)this;
        if (!entity.verticalCollisionBelow || !(entity.getBoundingBox() instanceof BodyBox box)
            || !GravityFrame.nonliving(entity)) return;
        var velocity = entity.getDeltaMovement();
        var up = box.up();
        double inward = velocity.dot(up);
        if (inward < 0) entity.setDeltaMovement(velocity.subtract(up.scale(inward)));
    }
    @ModifyReturnValue(method="makeBoundingBox", at=@At("RETURN"))
    private AABB positionedBody(AABB vanilla) {
        var entity = (Entity)(Object)this;
        return entity.getBoundingBox() instanceof BodyBox body ? body.reposition(vanilla, entity.position()) : vanilla;
    }

    @ModifyReturnValue(method="getEyeY", at=@At("RETURN"))
    private double eyeY(double upright) {
        var entity = (Entity)(Object)this;
        return entity.getBoundingBox() instanceof BodyBox body ? upright + (body.up().y - 1) * (entity.getEyeHeight() - body.pivotHeight()) : upright;
    }

    @ModifyReturnValue(method="getEyePosition()Lnet/minecraft/world/phys/Vec3;", at=@At("RETURN"))
    private Vec3 eyeXZ(Vec3 result) {
        var entity = (Entity)(Object)this;
        if (!(entity.getBoundingBox() instanceof BodyBox body)) return result;
        var up = body.up();
        double height = entity.getEyeHeight() - body.pivotHeight();
        return result.add(up.x * height, 0, up.z * height);
    }

    @ModifyReturnValue(method="getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at=@At("RETURN"))
    private Vec3 interpolatedEyes(Vec3 upright) {
        var entity = (Entity)(Object)this;
        return entity.getBoundingBox() instanceof BodyBox body
            ? upright.add(body.up().subtract(0, 1, 0).scale(entity.getEyeHeight() - body.pivotHeight())) : upright;
    }
}
