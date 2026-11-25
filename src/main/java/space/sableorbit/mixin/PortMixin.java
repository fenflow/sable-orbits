package space.sableorbit.mixin;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.orbit.OrbitalStation;
import com.ntmspace.tileentity.station.TileEntityOrbitalStation;
import net.minecraft.world.level.Level;
@Mixin(value=TileEntityOrbitalStation.class,remap=false)
abstract class PortMixin {
    @Redirect(method="serverTick", at=@At(value="INVOKE", target=
        "Lcom/ntmspace/orbit/OrbitalStation;getStationFromPosition(Lnet/minecraft/server/MinecraftServer;II)Lcom/ntmspace/orbit/OrbitalStation;"))
    private static OrbitalStation localStation(MinecraftServer server, int x, int z,
                                             @Local(argsOnly=true) Level level, @Local(argsOnly=true) BlockPos pos) {
        if (level instanceof ServerLevel orbit && OrbitSpaces.isolated(orbit)
                && Sable.HELPER.getContaining(orbit, pos) instanceof ServerSubLevel plot) {
            var station = Hooks.stationAtPlot(plot);
            if (station != null) return station;
        }
        return OrbitalStation.getStationFromPosition(server, x, z);
    }

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
