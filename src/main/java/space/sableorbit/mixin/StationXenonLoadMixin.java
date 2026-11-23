package space.sableorbit.mixin;

import com.ntmspace.tileentity.station.TileEntityXenonThruster;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.Hooks;

@Mixin(value = TileEntityXenonThruster.class, remap = false)
abstract class StationXenonLoadMixin {
    @Inject(method = "onGraphLoad", at = @At("HEAD"), cancellable = true)
    private void retainBurnOnLoad(ServerLevel level, CallbackInfo ci) {
        if (Hooks.stationInTransfer(level, ((TileEntityXenonThruster)(Object)this).getBlockPos()) != null) ci.cancel();
    }
}
