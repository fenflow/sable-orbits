package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.orbit.OrbitalStation;
import com.ntmspace.tileentity.station.TileEntityOrbitalStation;
import net.minecraft.world.level.Level;
@Mixin(value=TileEntityOrbitalStation.class,remap=false)
abstract class PortMixin {
    @Inject(method="serverTick",at=@At("TAIL"))
    private static void legacy(Level level,net.minecraft.core.BlockPos pos,net.minecraft.world.level.block.state.BlockState state,
            TileEntityOrbitalStation port,CallbackInfo ci) {
        if (port.isCore() && level instanceof net.minecraft.server.level.ServerLevel sl) Hooks.queueLegacy(sl,pos);
    }
    @Inject(method="dockRocket",at=@At("RETURN"))
    private void attach(com.ntmspace.orbit.StationDock craft,CallbackInfo ci) { Docking.attached((TileEntityOrbitalStation)(Object)this,craft); }
    @Redirect(method={"serverTick","clientTick"},at=@At(value="INVOKE",target="Lcom/ntmspace/orbit/OrbitalStation;update(Lnet/minecraft/world/level/Level;)V"))
    private static void clock(OrbitalStation station,Level level) {
        if (!OrbitSpaces.isolated(level)) station.update(level);
    }
}
