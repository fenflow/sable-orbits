package space.sableorbit.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import space.sableorbit.LaunchMotion;

@Mixin(Projectile.class)
abstract class ShotMixin {
    @WrapMethod(method="shootFromRotation")
    private void relativeAim(Entity shooter,float pitch,float yaw,float roll,float speed,float spread, Operation<Void> shoot) {
        shoot.call(shooter,pitch,yaw,roll,speed,spread);
        var shot = (Projectile)(Object)this;
        if (shot.level().isClientSide || LaunchMotion.carrier(shooter,shot.position()).lengthSqr()==0) return;
        // Vanilla's reported player motion can already contain the entire platform step.
        // Sable also adds its own living-entity carry at the return of this method.
        var known = shooter.getKnownMovement();
        var relative = (shooter instanceof net.minecraft.server.level.ServerPlayer ? Vec3.ZERO : shooter.getDeltaMovement()).subtract(known);
        if (shooter.onGround()) relative = relative.multiply(1,0,1);
        if (shooter instanceof LivingEntityMovementExtension living) {
            var carry = living.sable$getInheritedVelocity();
            relative = relative.subtract(carry.x,carry.y,carry.z);
        }
        shot.setDeltaMovement(shot.getDeltaMovement().add(relative));
    }
}
