package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import space.sableorbit.LaunchMotion;

@Mixin(value=com.hbm.entity.projectile.EntityBulletBaseMK4.class,remap=false)
abstract class SednaMotionMixin {
    @ModifyExpressionValue(method="tick",at=@At(value="FIELD",target="Lcom/hbm/items/weapon/sedna/BulletConfig;gravity:F"))
    private float gravity(float g) { return (float)(g*LaunchMotion.gravity((Entity)(Object)this)); }

    // Both the swept impact segment and the final displacement use this expression.
    @ModifyExpressionValue(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"),require=2)
    private Vec3 worldStep(Vec3 step) {
        var e=(Entity)(Object)this;
        return e.hasData(LaunchMotion.DRIFT) && com.ntmspace.orbit.Orbit.inOrbit(e.level()) ? step.add(e.getData(LaunchMotion.DRIFT)) : step;
    }
}
