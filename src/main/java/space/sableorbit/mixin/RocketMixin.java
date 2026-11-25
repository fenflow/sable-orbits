package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.entity.rocket.EntityRideableRocket;
import com.ntmspace.orbit.*;
import com.ntmspace.rocket.*;
import com.ntmspace.tileentity.station.TileEntityOrbitalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
@Mixin(value=EntityRideableRocket.class,remap=false)
abstract class RocketMixin {
    @org.spongepowered.asm.mixin.Shadow private com.ntmspace.rocket.ItemVOTVdrive.Destination destinationOverride;
    @org.spongepowered.asm.mixin.Unique private dev.golem.core.world.EntityInterpolation sableorbits$motion;
    @org.spongepowered.asm.mixin.Unique private int sableorbits$missingDockTicks;
    protected dev.golem.core.world.EntityInterpolation interpolation() {
        if(sableorbits$motion==null) sableorbits$motion=new dev.golem.core.world.EntityInterpolation((EntityRideableRocket)(Object)this,3);
        return sableorbits$motion;
    }
    @ModifyConstant(method="tick",constant=@Constant(floatValue=8f))
    private float dummyThreshold(float nativeThreshold) {
        var r=(EntityRideableRocket)(Object)this;
        return r.getEntityData().get(Berth.PORT).equals(BlockPos.ZERO)?nativeThreshold:Float.MAX_VALUE;
    }
    @Inject(method="positionRider",at=@At("HEAD"),cancellable=true)
    private void rotateSeat(net.minecraft.world.entity.Entity rider,net.minecraft.world.entity.Entity.MoveFunction move,CallbackInfo ci) {
        var r=(EntityRideableRocket)(Object)this;
        var pos=r.getEntityData().get(Berth.PORT);
        if(pos.equals(BlockPos.ZERO)) return;
        var ship=dev.ryanhcode.sable.Sable.HELPER.getContaining(r.level(),pos);
        if(ship==null)return;
        boolean player=rider instanceof net.minecraft.world.entity.player.Player;
        var seat=Berth.base(r,pos,r.getEntityData().get(Berth.GAP)).add(0,r.getMountedYOffset()+(player?1.12:0),0);
        var world=ship.logicalPose().transformPosition(seat);
        move.accept(rider,world.x,world.y-(player?1.62:0),world.z);ci.cancel();
    }
    @Inject(method="getDismountLocationForPassenger",at=@At("HEAD"),cancellable=true)
    private void portExit(net.minecraft.world.entity.LivingEntity rider,CallbackInfoReturnable<net.minecraft.world.phys.Vec3> result) {
        var r=(EntityRideableRocket)(Object)this;
        var pos=r.getEntityData().get(Berth.PORT);
        if(pos.equals(BlockPos.ZERO))return;
        var ship=dev.ryanhcode.sable.Sable.HELPER.getContaining(r.level(),pos);
        if(ship==null)return;
        for(int height=2;height<=5;height++) for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var exit=ship.logicalPose().transformPosition(pos.relative(direction).getCenter().add(0,height,0));
            if(r.level().noCollision(rider,rider.getBoundingBox().move(exit.subtract(rider.position())))) {result.setReturnValue(exit);return;}
        }
    }
    @Inject(method="<clinit>",at=@At("TAIL"))
    private static void registerBerthData(CallbackInfo ci) { Berth.initialize(); }
    @Inject(method="defineData",at=@At("TAIL"))
    private void berthData(dev.golem.core.data.SynchedData data,CallbackInfo ci) {
        data.define(Berth.PORT,BlockPos.ZERO);data.define(Berth.GAP,0f);
    }
    @Inject(method="serverMove",at=@At(value="INVOKE",target="Lcom/ntmspace/entity/rocket/EntityRideableRocket;motionMult()D"),cancellable=true)
    private void localFlight(CallbackInfo ci) {
        var r=(EntityRideableRocket)(Object)this;
        if(OrbitSpaces.isolated(r.level()) && (r.getState()==EntityRideableRocket.RocketState.DOCKING || !r.getEntityData().get(Berth.PORT).equals(BlockPos.ZERO))) ci.cancel();
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void follow(CallbackInfo ci) { var r=(EntityRideableRocket)(Object)this;
        if(r.level().isClientSide) interpolation().interpolate();
        else if (OrbitSpaces.isolated(r.level())) {
            boolean arriving = r.getState() == EntityRideableRocket.RocketState.DOCKING;
            Docking.follow(r);
            if (Berth.tick(r)) {
                if (++sableorbits$missingDockTicks >= 60) Berth.abortApproach(r);
            } else {
                sableorbits$missingDockTicks = 0;
            }
            if (!r.isRemoved() && !r.canExitCapsule()) Docking.keepFlightLoaded(r);
            if (arriving && r.getState() != EntityRideableRocket.RocketState.DOCKING) {
                destinationOverride = null;
                sableorbits$missingDockTicks = 0;
            }
        } else if (r.getState() == EntityRideableRocket.RocketState.DOCKING && !Orbit.inOrbit(r.level())) {
            // Older saves may still be waiting on the planet. Allow an already queued native teleport to finish first.
            if (++sableorbits$missingDockTicks >= 60) {
                Berth.abortApproach(r);
                destinationOverride = null;
                sableorbits$missingDockTicks = 0;
            }
        } else sableorbits$missingDockTicks = 0;
        BerthBox.tick(r);
    }
    @Inject(method="beginLandingSequence",at=@At(value="INVOKE",target="Lcom/ntmspace/entity/rocket/EntityRideableRocket;canRide()Z"),cancellable=true)
    private void validateArrival(RocketTarget from, RocketTarget to, CallbackInfo ci) {
        var rocket = (EntityRideableRocket)(Object)this;
        var server = rocket.getServer();
        if (server == null || !rocket.canRide()) return;
        var disk = rocket.getDestination();
        if (disk == null || disk.body != DriveBody.ORBIT) return;
        // Keep native stage separation for touchdown. A finless spent stage would tip and explode on impact.
        // Stop before NTM enters DOCKING and asks the teleporter to change dimensions.
        var root = Docking.addressed(server, disk.x * 1024, disk.z * 1024);
        var target = rocket.getPersistentData();
        if (root == null && !target.hasUUID("sableorbits_dock")) return;
        if (root != null && (!target.hasUUID("sableorbits_dock") || root.getUniqueId().equals(target.getUUID("sableorbits_dock")))
                && Docking.receivingPort(StationData.get(server).getStationFromPosition(disk.x * 1024, disk.z * 1024)) != null) return;
        Berth.abortApproach(rocket);
        destinationOverride = null;
        ci.cancel();
    }
    @Inject(method="attemptLaunch",at=@At("HEAD"),cancellable=true)
    private void requireDock(CallbackInfo ci) {
        var rocket = (EntityRideableRocket)(Object)this;
        if (rocket.getServer() == null) return;
        rocket.getPersistentData().remove("sableorbits_dock");
        if (OrbitSpaces.isolated(rocket.level()))
            rocket.getPersistentData().putDouble("sableorbits_departure",rocket.getY()-96);
        var disk = rocket.getDestination();
        if (disk == null || disk.body != DriveBody.ORBIT) return;
        var struct = rocket.getRocket();
        if (struct != null && struct.capsule != null && RocketParts.STATION_CORE.equals(struct.capsule.id())) return;
        var station = StationData.get(rocket.getServer()).getStationFromPosition(disk.x*1024,disk.z*1024);
        var dock = Docking.receivingPort(station);
        if (dock == null) {
            if (rocket.getFirstPassenger() instanceof net.minecraft.server.level.ServerPlayer p)
                p.displayClientMessage(net.minecraft.network.chat.Component.literal("The receiving station needs an available orbital dock."),false);
            ci.cancel();
        } else Docking.rememberDestination(rocket, dock);
    }
    @Redirect(method="recallPod",at=@At(value="INVOKE",target="Lcom/ntmspace/entity/rocket/EntityRideableRocket;attemptLaunch()V"))
    private void recallFromMovingPort(EntityRideableRocket rocket) {
        if (!OrbitSpaces.isolated(rocket.level()) || !(rocket.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            rocket.attemptLaunch(); return;
        }
        var tag=rocket.getPersistentData();
        var station=Docking.nearby(level,(int)rocket.getX(),(int)rocket.getZ());
        var target=rocket.getTarget();
        if (station==null || target==null || !rocket.canRecall() || rocket.hasRider()
                || !rocket.getRocket().hasSufficientFuel(station.orbiting,target.body(),true,target.inOrbit())) return;
        var pos=BlockPos.of(tag.getLong("sableorbits_port"));
        if (level.getBlockEntity(pos) instanceof TileEntityOrbitalStation dock && dock.getDocked()==rocket) dock.undockRocket();
        tag.remove("sableorbits_berth");
        tag.remove("hbm_sable_ship_port");
        tag.putDouble("sableorbits_departure",rocket.getY()-8);
        rocket.setState(EntityRideableRocket.RocketState.UNDOCKING);
        Docking.keepFlightLoaded(rocket);
    }
    @ModifyConstant(method="tick",constant=@Constant(doubleValue=32.0))
    private double undockingHeight(double normal) {
        var rocket=(EntityRideableRocket)(Object)this;
        if(OrbitSpaces.isolated(rocket.level()) && rocket.isUndocking() && !rocket.getEntityData().get(Berth.PORT).equals(BlockPos.ZERO))
            return rocket.getPersistentData().getFloat("sableorbits_gap")>=8 ? Double.MAX_VALUE : -Double.MAX_VALUE;
        return OrbitSpaces.isolated(rocket.level()) && rocket.getPersistentData().contains("sableorbits_departure")
            ? rocket.getPersistentData().getDouble("sableorbits_departure") : normal;
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lcom/ntmspace/tileentity/station/TileEntityOrbitalStation;getBlockPos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos dockPosition(TileEntityOrbitalStation port) { return Docking.position(port); }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lcom/ntmspace/orbit/OrbitalStation;getPort(Lnet/minecraft/world/level/Level;II)Lcom/ntmspace/tileentity/station/TileEntityOrbitalStation;"))
    private TileEntityOrbitalStation resolve(Level level,int x,int z) {
        var rocket = (EntityRideableRocket)(Object)this;
        if (OrbitSpaces.isolated(level) || rocket.getState() == EntityRideableRocket.RocketState.DOCKING && !Orbit.inOrbit(level)) return null;
        return OrbitalStation.getPort(level,x,z);
    }
}






