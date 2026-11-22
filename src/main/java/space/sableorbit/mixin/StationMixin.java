package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.orbit.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
@Mixin(value=OrbitalStation.class, remap=false)
abstract class StationMixin {
    @Inject(method="getStationFromPosition", at=@At("HEAD"), cancellable=true)
    private static void locate(MinecraftServer server, int x, int z, CallbackInfoReturnable<OrbitalStation> cir) {
        var station = Hooks.lookup(server,x,z);
        if (station != null) cir.setReturnValue(station);
    }
    @Inject(method="recallPod",at=@At("HEAD"),cancellable=true)
    private void confirmRecall(com.ntmspace.world.SolarSystem.Body body,int x,int z,CallbackInfoReturnable<Boolean> result) {
        var self=(OrbitalStation)(Object)this;
        var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if(server==null || Docking.addressed(server,self.dX*1024,self.dZ*1024)==null) return;
        for(var dock:((EquipmentAccess)(Object)this).sableorbits$ports().values()) {
            var pod=dock.getDocked();
            if(pod==null || !pod.isReusable() || !pod.canRecall() || pod.hasRider()) continue;
            pod.recall(Orbit.levelOf(body),x,z);
            if(pod.entity() instanceof com.ntmspace.entity.rocket.EntityRideableRocket rocket && rocket.isUndocking()) {
                result.setReturnValue(true);return;
            }
        }
        result.setReturnValue(false);
    }
    @Shadow private float getTotalThrust() { throw new AssertionError(); }

    @Inject(method="getTransferTime", at=@At("HEAD"), cancellable=true)
    private void mobileTransferTime(Level level, CallbackInfoReturnable<Integer> result) {
        if (!(level instanceof ServerLevel server) || !OrbitSpaces.isolated(level)) return;
        var station = (OrbitalStation)(Object)this;
        int duration = Hooks.mobileCruiseTime(server,station,getTotalThrust(),false);
        if (duration > 0) result.setReturnValue(duration);
    }

    @Inject(method="travelTo", at=@At("TAIL"))
    private void recordBurn(Level level, com.ntmspace.world.SolarSystem.Body body, CallbackInfo ci) {
        var station = (OrbitalStation)(Object)this;
        if (level instanceof ServerLevel server && OrbitSpaces.isolated(server)
                && station.state == OrbitalStation.StationState.LEAVING && station.stateTimer == 0 && station.target == body)
            Hooks.mobileCruiseTime(server,station,getTotalThrust(),true);
    }
    @Inject(method="travelTo", at=@At("HEAD"), cancellable=true)
    private void lock(Level level, com.ntmspace.world.SolarSystem.Body body, CallbackInfo ci) {
        if (level instanceof ServerLevel sl && Hooks.travelLocked(sl.getServer(), (OrbitalStation)(Object)this)) ci.cancel();
    }
    @Inject(method="addStation", at=@At("RETURN"))
    private static void deploy(MinecraftServer server,int x,int z,com.ntmspace.world.SolarSystem.Body body,CallbackInfo ci) {
        var orbit = server.getLevel(Orbit.ORBIT);
        if (orbit != null) OrbitalStation.spawn(orbit,x*1024+512,z*1024+512);
    }
    @Inject(method="spawn", at=@At("HEAD"), cancellable=true)
    private static void existing(ServerLevel level,int x,int z,CallbackInfo ci) {
        if (Docking.addressed(level.getServer(),x,z) != null) ci.cancel();
    }
    @Inject(method="spawn", at=@At("RETURN"))
    private static void assemble(ServerLevel level, int x, int z, CallbackInfo ci) { Hooks.regular(level,x,z); }
}
