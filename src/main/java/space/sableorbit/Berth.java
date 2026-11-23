package space.sableorbit;

import com.ntmspace.entity.rocket.EntityRideableRocket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.*;
import net.minecraft.world.phys.Vec3;

public final class Berth {
    public static final EntityDataAccessor<BlockPos> PORT=SynchedEntityData.defineId(EntityRideableRocket.class,EntityDataSerializers.BLOCK_POS);
    public static final EntityDataAccessor<Float> GAP=SynchedEntityData.defineId(EntityRideableRocket.class,EntityDataSerializers.FLOAT);
    public static void initialize() { /* Allocate tracked fields before the first entity data builder. */ }
    public static Vec3 base(EntityRideableRocket r,BlockPos pos,float gap) {
        return pos.getCenter().add(0,1-r.getBbHeight()-gap,0);
    }
    public static float renderGap(EntityRideableRocket r,float partial) {
        float gap=r.getEntityData().get(GAP);
        if(r.getState()==EntityRideableRocket.RocketState.DOCKING) return gap+.1f*(1-partial);
        return r.isUndocking()?Math.max(0,gap-.1f*(1-partial)):gap;
    }
    /** Returns whether an arriving rocket is waiting for its destination dock to load or be replaced. */
    public static boolean tick(EntityRideableRocket r) {
        var tag=r.getPersistentData();
        var state=r.getState();
        boolean arriving=state==EntityRideableRocket.RocketState.DOCKING;
        var dock=arriving ? Docking.port(r) : tag.contains("sableorbits_port") && r.level().getBlockEntity(BlockPos.of(tag.getLong("sableorbits_port"))) instanceof com.ntmspace.tileentity.station.TileEntityOrbitalStation d ? d:null;
        if(dock==null || (!arriving && !r.isUndocking() && !tag.hasUUID("sableorbits_berth"))) {
            r.getEntityData().set(PORT,BlockPos.ZERO);
            return arriving;
        }
        var pos=dock.getBlockPos();
        var part=dev.ryanhcode.sable.Sable.HELPER.getContaining(r.level(),pos);
        if (part == null) {
            r.getEntityData().set(PORT, BlockPos.ZERO);
            return arriving;
        }
        float gap=0;
        if(arriving) {
            gap=Math.max(0,tag.getFloat("sableorbits_gap")-.1f);
            if(!tag.contains("sableorbits_gap")) gap=12;
            if(dock.getDocked()!=null && dock.getDocked()!=r) gap=Math.max(gap,8);
            if(gap==0) {
                r.setState(r.isReusable()?EntityRideableRocket.RocketState.NEEDSFUEL:EntityRideableRocket.RocketState.LANDED);
                dock.dockRocket(r);
            }
        } else if(r.isUndocking()) gap=tag.getFloat("sableorbits_gap")+.1f;
        tag.putFloat("sableorbits_gap",gap);
        r.getEntityData().set(PORT,pos);
        r.getEntityData().set(GAP,gap);
        r.setPos(part.logicalPose().transformPosition(base(r,pos,gap)));
        r.setDeltaMovement(Vec3.ZERO);
        return false;
    }

    public static void abortApproach(EntityRideableRocket rocket) {
        var data = rocket.getPersistentData();
        data.remove("sableorbits_dock");
        data.remove("sableorbits_gap");
        data.remove("sableorbits_port");
        data.remove("sableorbits_berth");
        rocket.getEntityData().set(PORT, BlockPos.ZERO);
        rocket.getEntityData().set(GAP, 0f);
        rocket.setDeltaMovement(Vec3.ZERO);
        rocket.setState(rocket.isReusable() ? EntityRideableRocket.RocketState.NEEDSFUEL : EntityRideableRocket.RocketState.LANDED);
        if (rocket.getFirstPassenger() instanceof net.minecraft.server.level.ServerPlayer player) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "The receiving orbital dock is unavailable. The rocket has stopped its approach."), false);
        }
    }
}


