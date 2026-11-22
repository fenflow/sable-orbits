package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.mixinterface.EntityExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;
import space.sableorbit.Attitude;
import space.sableorbit.BodyBox;

@Mixin(value=SubLevelEntityCollision.class, remap=false)
abstract class BodyCollisionMixin {
    // Sable normally redirects the separating impulse along the walker's up axis.
    // In EVA keep the surface normal even when the swimmer is facing into a corner.
    @ModifyConstant(method="collide", constant=@Constant(doubleValue=.8))
    private static double alignWalkingContact(double threshold, Entity entity) {
        return entity instanceof Player player && Attitude.free(player) ? 2 : threshold;
    }

    @ModifyExpressionValue(method="tryStepUp", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;onGround()Z"))
    private static boolean stepOnlyWithGravity(boolean grounded, Entity entity) {
        return grounded && !(entity instanceof Player player && Attitude.free(player));
    }

    // Sable's first two queries are the swept world broad phase. Later queries size the OBB.
    @ModifyExpressionValue(method="collide", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
        slice=@Slice(from=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;", ordinal=2)))
    private static AABB bodyDimensions(AABB bounds) {
        return bounds instanceof BodyBox body ? body.upright : bounds;
    }

    @ModifyExpressionValue(method={"transformEntityBoundsCenter", "getSubLevelEntityCollisionShape"},
        at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private static AABB uprightDimensions(AABB bounds) {
        return bounds instanceof BodyBox body ? body.upright : bounds;
    }

    @ModifyExpressionValue(method="collide", at=@At(value="INVOKE",
        target="Ldev/ryanhcode/sable/api/entity/EntitySubLevelUtil;getCustomEntityOrientation(Lnet/minecraft/world/entity/Entity;F)Lorg/joml/Quaterniondc;"))
    private static Quaterniondc bodyRotation(Quaterniondc original, Entity entity) {
        return entity.getBoundingBox() instanceof BodyBox body ? body.rotation() : original;
    }

    @ModifyExpressionValue(method="collide", at=@At(value="INVOKE",
        target="Ldev/ryanhcode/sable/sublevel/entity_collision/SubLevelEntityCollision;getHitBoxYaw(Ldev/ryanhcode/sable/companion/math/Pose3dc;)D"))
    private static double bodyHeading(double deckYaw, Entity entity) {
        return entity.getBoundingBox() instanceof BodyBox ? 0 : deckYaw;
    }

    // Deck contact pivots at the feet. Free swimmers use Sable's native eye pivot.
    @ModifyExpressionValue(method={"collide", "transformEntityBoundsCenter"}, at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/Entity;getEyeHeight()F"))
    private static float footPivot(float height, @Local(argsOnly=true) Entity entity) {
        return entity.getBoundingBox() instanceof BodyBox body ? body.pivotHeight() : height;
    }

    // Sable temporarily writes the transformed base of the collider to measure how far
    // the hull carried it. Minecraft stores an eye-pivoted swimmer's position vertically
    // below the eye. Convert back to that anchor before Sable differences the positions.
    @WrapOperation(method="collide", at=@At(value="INVOKE", ordinal=0,
        target="Ldev/ryanhcode/sable/mixinterface/EntityExtension;sable$setPosSuperRaw(Lnet/minecraft/world/phys/Vec3;)V"))
    private static void swimmerPosition(EntityExtension raw, Vec3 feet, Operation<Void> original, @Local(argsOnly=true) Entity entity) {
        if (entity.getBoundingBox() instanceof BodyBox body && body.pivotHeight() != 0)
            feet = feet.add(body.up().subtract(0, 1, 0).scale(body.pivotHeight()));
        original.call(raw, feet);
    }

    @WrapOperation(method="collide", at=@At(value="INVOKE",
        target="Ldev/ryanhcode/sable/ActiveSableCompanion;getFeetPos(Lnet/minecraft/world/entity/Entity;FLorg/joml/Quaterniondc;)Lorg/joml/Vector3d;"))
    private static Vector3d feet(ActiveSableCompanion helper, Entity entity, float offset, Quaterniondc rotation,
                                 Operation<Vector3d> original) {
        return original.call(helper, entity, offset, entity.getBoundingBox() instanceof BodyBox body && body.pivotHeight() == 0 ? null : rotation);
    }
}
