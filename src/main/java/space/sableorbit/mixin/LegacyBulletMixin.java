package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import space.sableorbit.LaunchMotion;

@Mixin(value=com.hbm.entity.projectile.EntityBullet.class,remap=false)
abstract class LegacyBulletMixin {
    @ModifyExpressionValue(method="tick",at=@At(value="FIELD",target="Lcom/hbm/entity/projectile/EntityBullet;gravity:D"))
    private double gravity(double g) { return g*LaunchMotion.gravity((Entity)(Object)this); }
    @ModifyArg(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;",ordinal=0))
    private double drag(double drag) { return LaunchMotion.vacuumDrag((Entity)(Object)this, drag); }
}
