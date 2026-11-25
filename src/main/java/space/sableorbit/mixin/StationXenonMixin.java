package space.sableorbit.mixin;

import com.hbm.lib.ForgeDirection;
import com.ntmspace.tileentity.station.OrbitConnector;
import com.ntmspace.tileentity.station.TileEntityXenonThruster;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.Hooks;

@Mixin(value = TileEntityXenonThruster.class, remap = false)
abstract class StationXenonMixin implements OrbitConnector {
    @Override
    public boolean canConnect(ForgeDirection side) {
        if (OrbitConnector.super.canConnect(side)) return true;
        var thruster = (TileEntityXenonThruster)(Object)this;
        return side != ForgeDirection.UNKNOWN && Hooks.landedStation(thruster.getLevel(), thruster.getBlockPos());
    }

    @Inject(method = "onGraphLoad", at = @At("HEAD"), cancellable = true)
    private void retainBurnOnLoad(ServerLevel level, CallbackInfo ci) {
        if (Hooks.stationInTransfer(level, ((TileEntityXenonThruster)(Object)this).getBlockPos()) != null) ci.cancel();
    }
}
