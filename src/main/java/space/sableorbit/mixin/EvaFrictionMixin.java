package space.sableorbit.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import space.sableorbit.Spacewalk;

@Mixin(LivingEntity.class)
abstract class EvaFrictionMixin {
    @Inject(method="shouldDiscardFriction",at=@At("HEAD"),cancellable=true)
    private void coast(CallbackInfoReturnable<Boolean> result) {
        if (Spacewalk.drifting((LivingEntity)(Object)this)) result.setReturnValue(true);
    }
    @ModifyConstant(method="aiStep",constant=@Constant(doubleValue=0.003))
    private double coastAtLowSpeed(double threshold) {
        return Spacewalk.drifting((LivingEntity)(Object)this) ? 0 : threshold;
    }
}
