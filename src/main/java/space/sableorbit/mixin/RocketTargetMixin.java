package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.rocket.RocketTarget;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
@Mixin(value=RocketTarget.class,remap=false)
abstract class RocketTargetMixin {
    @Inject(method="of",at=@At("HEAD"),cancellable=true)
    private static void body(Level level,int x,int z,CallbackInfoReturnable<RocketTarget> cir) {
        if (!(level instanceof ServerLevel sl) || !OrbitSpaces.isolated(level)) return;
        var station = Docking.nearby(sl,x,z);
        if (station != null) cir.setReturnValue(new RocketTarget(station.orbiting,true,true));
    }
}
