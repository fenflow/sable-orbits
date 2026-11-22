package space.sableorbit.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import space.sableorbit.LaunchMotion;

@Mixin(FishingHook.class)
abstract class FishingGravityMixin {
    @ModifyConstant(method="tick", constant=@Constant(doubleValue=-0.03))
    private double localGravity(double gravity) {
        return gravity * LaunchMotion.gravity((FishingHook)(Object)this);
    }

    @ModifyConstant(method="tick", constant=@Constant(doubleValue=0.92))
    private double airDrag(double drag) {
        return LaunchMotion.vacuumDrag((FishingHook)(Object)this, drag);
    }
}
