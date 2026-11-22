package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=com.ntmspace.orbit.Orbit.class, remap=false)
abstract class OrbitMixin {
    @Inject(method="inOrbit", at=@At("HEAD"), cancellable=true)
    private static void mobile(net.minecraft.world.level.Level level, CallbackInfoReturnable<Boolean> cir) {
        if (level != null && OrbitSpaces.isolated(level)) cir.setReturnValue(true);
    }
}
