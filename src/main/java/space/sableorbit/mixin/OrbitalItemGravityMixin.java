package space.sableorbit.mixin;

import com.ntmspace.orbit.Orbit;
import com.ntmspace.orbit.OrbitEnvironment;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=OrbitEnvironment.class,remap=false)
abstract class OrbitalItemGravityMixin {
    @Inject(method="tickItem", at=@At("HEAD"), cancellable=true)
    private static void preserveDrift(ItemEntity item, CallbackInfo ci) {
        // Native orbit overwrites vertical speed and damps horizontal speed to offset
        // vanilla gravity. The entity gravity hook already supplies the orbital value.
        if (Orbit.inOrbit(item.level())) ci.cancel();
    }
}
