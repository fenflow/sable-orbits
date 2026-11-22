package space.sableorbit;

import com.ntmspace.entity.rocket.EntityRideableRocket;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.*;
import java.util.Optional;

/** World bounds for entity queries, with the exact port-local box for picking. */
public final class BerthBox extends AABB {
    public static final java.util.Set<EntityRideableRocket> clientRockets=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final AABB local;
    private final Pose3dc pose;
    private BerthBox(AABB local,Pose3dc pose,BoundingBox3d bounds) {
        super(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ());
        this.local=local;
        this.pose=pose;
    }
    private static BerthBox of(AABB local,Pose3dc pose) {
        return new BerthBox(local,pose,new BoundingBox3d(local).transform(pose));
    }
    public static void update(EntityRideableRocket rocket,Pose3dc pose,float gap) {
        var pos=rocket.getEntityData().get(Berth.PORT);
        var base=Berth.base(rocket,pos,gap);
        double w=rocket.getBbWidth()/2;
        rocket.setBoundingBox(of(new AABB(base.add(-w,0,-w),base.add(w,rocket.getBbHeight(),w)),new Pose3d(pose)));
    }
    public static void tick(EntityRideableRocket rocket) {
        var pos=rocket.getEntityData().get(Berth.PORT);
        if(!pos.equals(BlockPos.ZERO)) {
            var ship=Sable.HELPER.getContaining(rocket.level(),pos);
            if(ship!=null) {if(rocket.level().isClientSide) clientRockets.add(rocket);update(rocket,ship.logicalPose(),rocket.getEntityData().get(Berth.GAP));return;}
        }
        if(rocket.level().isClientSide)clientRockets.remove(rocket);
        if(rocket.getBoundingBox() instanceof BerthBox) rocket.setBoundingBox(rocket.getDimensions(rocket.getPose()).makeBoundingBox(rocket.position()));
    }
    @Override public Optional<Vec3> clip(Vec3 start,Vec3 end) {
        return local.clip(pose.transformPositionInverse(start),pose.transformPositionInverse(end)).map(pose::transformPosition);
    }
    @Override public boolean contains(Vec3 point) { return local.contains(pose.transformPositionInverse(point)); }
    @Override public AABB inflate(double amount) { return amount==0 ? this : of(local.inflate(amount),pose); }
}

