package space.sableorbit.mixin;

import com.ntmspace.entity.rocket.EntityRideableRocketDummy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import space.sableorbit.Berth;

@Mixin(value=EntityRideableRocketDummy.class,remap=false)
abstract class DockDummyMixin {
    @Inject(method="isPickable",at=@At("HEAD"),cancellable=true)
    private void parentHandlesDockedClicks(CallbackInfoReturnable<Boolean> result) {
        var parent=((EntityRideableRocketDummy)(Object)this).parent;
        if(parent!=null && !parent.getEntityData().get(Berth.PORT).equals(BlockPos.ZERO)) result.setReturnValue(false);
    }
}
