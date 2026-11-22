package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import dev.ryanhcode.sable.sublevel.SubLevel;
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets="dev.simulated_team.simulated.util.SimAssemblyHelper",remap=false)
abstract class AssemblerMixin {
    @Inject(method="disassembleSubLevel", at=@At("HEAD"), cancellable=true)
    private static void protect(Level level, SubLevel part, BlockPos a, BlockPos b, Rotation rotation, boolean effects, CallbackInfo ci) {
        if (com.ntmspace.orbit.Orbit.inOrbit(level)) ci.cancel();
    }
}
