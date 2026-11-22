package space.sableorbit.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import space.sableorbit.LaunchMotion;

@Mixin({AbstractArrow.class,ThrowableProjectile.class,LlamaSpit.class})
abstract class VacuumDragMixin {
    @ModifyArg(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"))
    private double orbitalDrag(double factor) { return LaunchMotion.vacuumDrag((Entity)(Object)this,factor); }
}
