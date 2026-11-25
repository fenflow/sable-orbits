package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ntmspace.block.station.MachineXenonThruster;
import com.ntmspace.tileentity.station.TileEntityXenonThruster;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.Hooks;

@Mixin(value = MachineXenonThruster.class, remap = false)
abstract class StationXenonIntakeMixin {
    @ModifyReturnValue(method = {"lambda$caps$0", "lambda$caps$1"}, at = @At("RETURN"))
    private static boolean allowLandedIntake(boolean inOrbit, TileEntityXenonThruster thruster, Direction face) {
        return inOrbit || Hooks.landedStation(thruster.getLevel(), thruster.getBlockPos());
    }
}
