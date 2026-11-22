package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ntmspace.orbit.Orbit;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.LaunchMotion;

@Mixin(FireworkRocketEntity.class)
abstract class FireworkMotionMixin {
    @WrapOperation(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 rocketAcceleration(Vec3 motion, double x, double y, double z, Operation<Vec3> multiply) {
        var rocket = (FireworkRocketEntity)(Object)this;
        if (!rocket.hasData(LaunchMotion.DRIFT) || !Orbit.inOrbit(rocket.level()))
            return multiply.call(motion, x, y, z);
        var drift = rocket.getData(LaunchMotion.DRIFT);
        return multiply.call(motion.subtract(drift), x, y, z).add(drift);
    }
}
