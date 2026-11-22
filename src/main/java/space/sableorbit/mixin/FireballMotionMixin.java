package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ntmspace.orbit.Orbit;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.LaunchMotion;

@Mixin(AbstractHurtingProjectile.class)
abstract class FireballMotionMixin {
    @WrapOperation(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/phys/Vec3;normalize()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 thrustDirection(Vec3 motion, Operation<Vec3> normalize) {
        var projectile = (AbstractHurtingProjectile)(Object)this;
        if (projectile.hasData(LaunchMotion.DRIFT) && Orbit.inOrbit(projectile.level()))
            motion = motion.subtract(projectile.getData(LaunchMotion.DRIFT));
        return normalize.call(motion);
    }

    @ModifyExpressionValue(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/projectile/AbstractHurtingProjectile;getInertia()F"))
    private float airDrag(float drag) {
        return (float)LaunchMotion.vacuumDrag((AbstractHurtingProjectile)(Object)this, drag);
    }
}
