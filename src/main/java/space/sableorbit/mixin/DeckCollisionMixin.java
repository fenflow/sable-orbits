package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.EntityExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.DeckFrame;
import space.sableorbit.Spacewalk;

@Mixin(value=SubLevelEntityCollision.class, remap=false)
abstract class DeckCollisionMixin {
    @Unique private static final ThreadLocal<DeckFrame.Step> sableorbits$collision = new ThreadLocal<>();

    @WrapMethod(method="collide")
    private static CollisionInfo relativeCollision(Entity entity, Vec3 motion, Vec3 carry, LevelReusedVectors scratch, Operation<CollisionInfo> solve) {
        if (!(entity instanceof LocalPlayer player) || !((Spacewalk.Travel)player).sableorbits$takeDeckStep())
            return solve.call(entity, motion, carry, scratch);
        var frame = DeckFrame.step(player);
        if (frame == null) return solve.call(entity, motion, carry, scratch);
        var step = frame.translation;
        var start = entity.position();
        var bounds = entity.getBoundingBox();
        var raw = (EntityExtension)entity;
        var previous = sableorbits$collision.get();
        try {
            // Compare both bodies at the same origin. Sable can then change contact at any
            // collision substep without losing or duplicating part of the ship's translation.
            sableorbits$collision.set(frame);
            raw.sable$setPosSuperRaw(start.add(step));
            entity.setBoundingBox(bounds.move(step));
            var result = solve.call(entity, motion, frame.relativeCarry, scratch);
            // Sable also retains the old hull while any non-air block is in its expanded
            // search box. Free the pilot when even the closest solid shape was out of reach.
            if (!result.verticalCollisionBelow && frame.leftDeck(result.trackingSubLevel))
                result.trackingSubLevel = null;
            result.inheritedMotion = result.inheritedMotion == null ? step : result.inheritedMotion.add(step);
            return result;
        } finally {
            raw.sable$setPosSuperRaw(start);
            entity.setBoundingBox(bounds);
            if (previous == null) sableorbits$collision.remove(); else sableorbits$collision.set(previous);
        }
    }

    @ModifyExpressionValue(method="collide", at=@At(value="INVOKE",
        target="Ldev/ryanhcode/sable/companion/math/Pose3d;set(Ldev/ryanhcode/sable/companion/math/Pose3dc;)Ldev/ryanhcode/sable/companion/math/Pose3d;"))
    private static Pose3d relativePreviousPose(Pose3d copy) {
        var check = sableorbits$collision.get();
        if (check != null) copy.position().add(check.translation.x, check.translation.y, check.translation.z);
        return copy;
    }

    @WrapOperation(method="collide", at=@At(value="INVOKE", target="Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;sat(Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private static Vector3d recordContact(OrientedBoundingBox3d player, OrientedBoundingBox3d block, Vector3d result,
                                          Operation<Vector3d> test, @Local(name="subLevel") SubLevel deck) {
        var separation = test.call(player, block, result);
        var check = sableorbits$collision.get();
        if (check != null) check.checkContact(deck,player,block,separation);
        return separation;
    }
}
