package space.sableorbit.mixin;

import com.ntmspace.orbit.OrbitEnvironment;
import dev.ryanhcode.sable.Sable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.WaterAnimal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.GravityFrame;
import space.sableorbit.OrbitSpaces;

@Mixin(value = OrbitEnvironment.class, remap = false)
abstract class CabinGravityMixin {
    @Inject(method = "gravity", at = @At("HEAD"), cancellable = true)
    private static void mobileCabinGravity(LivingEntity living, CallbackInfoReturnable<Float> result) {
        if (OrbitSpaces.isolated(living.level()) && !(living instanceof WaterAnimal) && Sable.HELPER.getContaining(living) == null)
            result.setReturnValue(GravityFrame.orbitalGravity(living));
    }
}
