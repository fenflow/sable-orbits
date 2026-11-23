package space.sableorbit.mixin;

import com.ntmspace.orbit.Propulsion;
import com.ntmspace.tileentity.station.TileEntityStationPropulsionCreative;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.Hooks;

@Pseudo
@Mixin(targets = "pl.tajemny4.hbmsable.space.ShipThrusters", remap = false)
abstract class ShipThrusterMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void keepTransferBurn(Level level, BlockPos pos, BlockState state, BlockEntity tile, CallbackInfo ci) {
        // Redstone thrust and NTM travel both write the same native burn flag.
        if (tile instanceof Propulsion && !(tile instanceof TileEntityStationPropulsionCreative)
                && Hooks.stationInTransfer(level, pos) != null) ci.cancel();
    }
}
