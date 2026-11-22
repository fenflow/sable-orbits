package space.sableorbit.mixin;

import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.*;

@Mixin(Player.class)
abstract class EvaPlayerMixin implements Spacewalk.Travel {
    // Even on the space diagonal the rotating box spans less than a block.
    // Centering it on the eye also keeps the collision center still during a turn.
    @Unique private static final EntityDimensions sableorbits$swimmer = EntityDimensions.scalable(.56f, .56f).withEyeHeight(.28f);
    @Unique private boolean sableorbits$wasFree;

    @ModifyReturnValue(method="getDefaultDimensions", at=@At("RETURN"))
    private EntityDimensions compactSwimmer(EntityDimensions normal, Pose pose) {
        return pose == Pose.SWIMMING && Attitude.free((Player)(Object)this) ? sableorbits$swimmer : normal;
    }

    @Inject(method="canPlayerFitWithinBlocksAndEntitiesWhen", at=@At("HEAD"), cancellable=true)
    private void tiltedStanceClearance(net.minecraft.world.entity.Pose pose,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> result) {
        var player = (Player)(Object)this;
        if (player.getBoundingBox() instanceof BodyBox body) result.setReturnValue(BodyClearance.fits(player, pose, body));
    }

    @Inject(method="updatePlayerPose", at=@At("HEAD"), cancellable=true)
    private void swimInVacuum(CallbackInfo ci) {
        var player = (Player)(Object)this;
        boolean free = Attitude.free(player);
        if (sableorbits$wasFree != free) {
            sableorbits$wasFree = free;
            if (player.hasPose(Pose.SWIMMING)) player.refreshDimensions();
        }
        if (player.getForcedPose() == null && player.isAlive() && free) {
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        }
    }

    @Unique private boolean sableorbits$deckStep;

    @Override public boolean sableorbits$takeDeckStep() {
        boolean step = sableorbits$deckStep;
        sableorbits$deckStep = false;
        return step;
    }

    @Unique private final Vector3d sableorbits$freeCarry = new Vector3d();
    @Unique private boolean sableorbits$coasting;
    @Inject(method="travel",at=@At("HEAD"))
    private void rememberFreeCarry(Vec3 input, CallbackInfo ci) {
        var p=(Player)(Object)this;
        sableorbits$deckStep = p.level().isClientSide && p.isControlledByLocalInstance() && OrbitSpaces.isolated(p.level());
        sableorbits$coasting = p.isControlledByLocalInstance() && Spacewalk.drifting(p)
            && ((EntityMovementExtension)p).sable$getTrackingSubLevel() == null;
        if (sableorbits$coasting)
            sableorbits$freeCarry.set(((LivingEntityMovementExtension)p).sable$getInheritedVelocity());
    }

    @Inject(method="travel",at=@At("RETURN"))
    private void preserveFreeCarry(Vec3 input, CallbackInfo ci) {
        sableorbits$deckStep = false;
        if (!sableorbits$coasting) return;
        var p=(Player)(Object)this;
        var movement=(EntityMovementExtension)p;
        var collision=movement.sable$getCollisionInfo();
        // Sable already moves the player by the inherited velocity, separately from vanilla travel.
        // Preserve that channel against free-space drag only. Contact owns the new velocity.
        if (!p.horizontalCollision && !p.verticalCollision && !p.onGround() && movement.sable$getTrackingSubLevel()==null
                && (collision==null || (collision.inheritedMotion==null &&
                    (collision.firstCollisions==null || collision.firstCollisions.isEmpty()))))
            ((LivingEntityMovementExtension)p).sable$getInheritedVelocity().set(sableorbits$freeCarry);
    }
    @ModifyConstant(method="travel",constant=@Constant(doubleValue=0.6))
    private double verticalFlightDrag(double multiplier) {
        return Spacewalk.drifting((Player)(Object)this) ? 1 : multiplier;
    }

    // Creative flight restores the pre-travel world Y speed. Camera-relative input
    // can accelerate along Y even with only W held.
    @ModifyArg(method="travel",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;setDeltaMovement(DDD)V",ordinal=0),index=1,
        slice=@Slice(from=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;travel(Lnet/minecraft/world/phys/Vec3;)V",ordinal=0)))
    private double retainVerticalSteering(double vanilla, @com.llamalad7.mixinextras.sugar.Local(argsOnly=true) Vec3 input) {
        var player = (Player)(Object)this;
        if (input.lengthSqr() < 1.0e-7 || !Attitude.free(player)) return vanilla;
        var direction = Attitude.freeLook(player).transform(input.toVector3f());
        return Math.abs(direction.y) > 1.0e-6 ? player.getDeltaMovement().y : vanilla;
    }
}
