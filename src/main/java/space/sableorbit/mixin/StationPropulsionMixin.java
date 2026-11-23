package space.sableorbit.mixin;

import com.ntmspace.orbit.Propulsion;
import com.ntmspace.tileentity.station.TileEntityMachineHTR3;
import com.ntmspace.tileentity.station.TileEntityMachineHTRF4;
import com.ntmspace.tileentity.station.TileEntityMachineHTRNeo;
import com.ntmspace.tileentity.station.TileEntityMachineLPW2Space;
import com.ntmspace.tileentity.station.TileEntityXenonThruster;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.Hooks;

@Mixin(value = {TileEntityMachineHTR3.class, TileEntityMachineHTRF4.class, TileEntityMachineHTRNeo.class,
    TileEntityMachineLPW2Space.class, TileEntityXenonThruster.class}, priority = 1100, remap = false)
abstract class StationPropulsionMixin {
    @Shadow(remap = false) private boolean hasRegistered;

    @Inject(method = "serverTick", at = @At("HEAD"))
    private static void resumeTransfer(Level level, BlockPos pos, BlockState state, @Coerce BlockEntity tile, CallbackInfo ci) {
        var self = (StationPropulsionMixin)(Object)tile;
        if (self.hasRegistered) return;
        var station = Hooks.stationInTransfer(level, pos);
        if (station == null) return;
        // The native save already contains the burn flag. Register without clearing it on the first tick.
        ((EquipmentAccess)(Object)station).sableorbits$engines().add((Propulsion)tile);
        self.hasRegistered = true;
    }
}
