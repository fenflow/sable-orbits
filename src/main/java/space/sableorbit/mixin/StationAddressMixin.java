package space.sableorbit.mixin;

import com.ntmspace.orbit.StationData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.StationLife;

@Mixin(value=StationData.class,remap=false)
abstract class StationAddressMixin {
    @Inject(method="findFreeSpace",at=@At("RETURN"),cancellable=true)
    private void skipLanded(CallbackInfoReturnable<int[]> result) {
        var a=result.getReturnValue();
        var server=ServerLifecycleHooks.getCurrentServer();
        if(server==null || a==null || !StationLife.reserved(server,a[0],a[1])) return;
        var data=(StationData)(Object)this;
        int limit=Math.max(1,com.ntmspace.config.SpaceConfig.maxStationDistance/1024);
        for(int x=-limit;x<limit;x++) for(int z=-limit;z<limit;z++) {
            if(!StationLife.reserved(server,x,z) && data.getStationFromPosition(x*1024,z*1024)==null) {
                result.setReturnValue(new int[]{x,z});return;
            }
        }
        throw new StationLife.AddressSpaceFull();
    }
}
