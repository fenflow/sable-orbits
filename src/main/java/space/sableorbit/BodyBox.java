package space.sableorbit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Quaternionfc;

import java.util.Optional;

/** Broad-phase world bounds, with an exact body-local shape for rays and Sable collision. */
public final class BodyBox extends AABB {
    private final AABB local;
    private final Pose3d pose;
    public final AABB upright;

    private BodyBox(AABB local, Pose3d pose, BoundingBox3d bounds) {
        super(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
        this.local = local;
        this.pose = pose;
        this.upright = local.move(pose.position().x, pose.position().y - pivotHeight(), pose.position().z);
    }

    private static BodyBox of(AABB local, Pose3d pose) {
        return new BodyBox(local, pose, new BoundingBox3d(local).transform(pose));
    }

    public static BodyBox at(AABB uprightWorld, Vec3 feet, Quaternionfc rotation) {
        return at(uprightWorld, feet, rotation, 0);
    }

    static BodyBox at(AABB uprightWorld, Vec3 feet, Quaternionfc rotation, float pivotHeight) {
        var pose = new Pose3d();
        pose.rotationPoint().set(0, pivotHeight, 0);
        pose.position().set(feet.x, feet.y + pivotHeight, feet.z);
        pose.orientation().set(rotation).normalize();
        return of(uprightWorld.move(-feet.x, -feet.y, -feet.z), pose);
    }

    public Quaterniondc rotation() { return pose.orientation(); }
    public float pivotHeight() { return (float)pose.rotationPoint().y; }

    public AABB reposition(AABB vanilla, Vec3 feet) {
        var updated = new Pose3d(pose);
        updated.position().set(feet.x, feet.y + pivotHeight(), feet.z);
        return of(vanilla.move(-feet.x, -feet.y, -feet.z), updated);
    }

    public static void refresh(Entity entity) {
        var frame = GravityFrame.resolve(entity);
        var current = entity.getBoundingBox();
        // Sable supplies the pose and collision bounds of mounted riders. A bed also
        // owns the sleeping pose, including when either is inside an oxygen cabin.
        if (!entity.isPassenger() && !entity.hasPose(net.minecraft.world.entity.Pose.SLEEPING)
                && (frame.deck() != null || (frame.free() && entity instanceof Player))) {
            var orientation = entity instanceof LivingEntity living ? Attitude.body(living) : Attitude.deckBasis(frame);
            // Upright bodies retain the original square footprint when turning in place.
            if (orientation.x * orientation.x + orientation.z * orientation.z > 1.0e-9f) {
                float pivot = frame.free() ? entity.getEyeHeight() : 0;
                if (current instanceof BodyBox box && box.pivotHeight() == pivot && Math.abs(box.rotation().x() * orientation.x
                    + box.rotation().y() * orientation.y + box.rotation().z() * orientation.z
                    + box.rotation().w() * orientation.w) > 1.0 - 1.0e-9) return;
                var upright = current instanceof BodyBox box ? box.upright : current;
                entity.setBoundingBox(at(upright, entity.position(), orientation, pivot));
                return;
            }
        }
        if (current instanceof BodyBox box) entity.setBoundingBox(box.upright);
    }

    public Vec3 up() {
        var up = rotation().transform(0, 1, 0, new org.joml.Vector3d());
        return new Vec3(up.x, up.y, up.z);
    }

    @Override public AABB move(double x, double y, double z) {
        var shifted = new Pose3d(pose);
        shifted.position().add(x, y, z);
        return of(local, shifted);
    }

    @Override public AABB inflate(double value) {
        return value == 0 ? this : of(local.inflate(value), pose);
    }

    @Override public Optional<Vec3> clip(Vec3 start, Vec3 end) {
        return local.clip(pose.transformPositionInverse(start), pose.transformPositionInverse(end))
            .map(pose::transformPosition);
    }

    @Override public boolean contains(double x, double y, double z) {
        return local.contains(pose.transformPositionInverse(new Vec3(x, y, z)));
    }
}
