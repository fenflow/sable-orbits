package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import dev.ryanhcode.sable.physics.config.dimension_physics.*;
import java.util.Optional;
import net.minecraft.world.level.Level;
@Mixin(value=DimensionPhysicsData.class,remap=false)
abstract class OrbitPhysicsMixin {
    private static final DimensionPhysics VACUUM = new DimensionPhysics(com.ntmspace.orbit.Orbit.ORBIT.location(),100,
        Optional.of(0f),Optional.of(new org.joml.Vector3f()),Optional.of(0d),Optional.empty(),Optional.empty(),false);
    @Inject(method="of", at=@At("HEAD"), cancellable=true)
    private static void orbit(Level level, CallbackInfoReturnable<DimensionPhysics> cir) {
        if (OrbitSpaces.isolated(level)) cir.setReturnValue(VACUUM);
    }
}
