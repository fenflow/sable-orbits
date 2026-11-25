package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.tileentity.rocket.TileEntityLaunchPadRocket;
import com.ntmspace.orbit.*;
import com.ntmspace.rocket.RocketParts;
import net.minecraft.server.level.ServerLevel;
@Mixin(value=TileEntityLaunchPadRocket.class,remap=false)
abstract class LaunchPadMixin {
    @Inject(method="canLaunch",at=@At("RETURN"),cancellable=true)
    private void checkDock(CallbackInfoReturnable<Boolean> cir) {
        var pad = (TileEntityLaunchPadRocket)(Object)this;
        if (!cir.getReturnValue() || !(pad.getLevel() instanceof ServerLevel level)) return;
        if (pad.rocket != null && pad.rocket.capsule != null && RocketParts.STATION_CORE.equals(pad.rocket.capsule.id())) return;
        var disk = NavDrives.destination(pad.getItem(TileEntityLaunchPadRocket.SLOT_DRIVE));
        if (disk == null || !disk.isOrbit()) return;
        var station = StationData.get(level.getServer()).getStationFromPosition(disk.x()*1024,disk.z()*1024);
        if (Docking.receivingPort(station) == null) cir.setReturnValue(false);
    }
}
