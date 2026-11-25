package space.sableorbit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LevelExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** Collision checks for rotating bodies and placement footprints. */
public final class BodyClearance {
    static boolean fuselageFits(Level level, SubLevel plot, AABB bounds) {
        var pose = plot == null ? null : plot.logicalPose();
        var worldBounds = pose == null ? bounds : new BoundingBox3d(bounds).transform(pose).toMojang();
        var nearby = level.getEntitiesOfClass(LivingEntity.class, worldBounds,
            entity -> entity.isAlive() && !entity.isSpectator());
        if (nearby.isEmpty()) return true;
        var scratch = ((LevelExtension)level).sable$getJOMLSink();
        var center = worldBounds.getCenter();
        var identity = new Quaterniond();
        var footprint = new OrientedBoundingBox3d(center.x, center.y, center.z, bounds.getXsize(), bounds.getYsize(),
            bounds.getZsize(), pose == null ? identity : pose.orientation(), scratch);
        if (pose != null) footprint.getDimensions().mul(pose.scale()).absolute();
        var separation = new Vector3d();
        for (var entity : nearby) {
            var box = entity.getBoundingBox();
            var size = box instanceof BodyBox body ? body.upright : box;
            var position = box.getCenter();
            var collider = new OrientedBoundingBox3d(position.x, position.y, position.z, size.getXsize(), size.getYsize(),
                size.getZsize(), box instanceof BodyBox body ? body.rotation() : identity, scratch);
            OrientedBoundingBox3d.sat(footprint, collider, separation);
            if (separation.x != Double.MAX_VALUE && separation.lengthSquared() > 1e-12) return false;
        }
        return true;
    }

    public interface Connection {
        Vec3 sableorbits$acceptedFeet();
    }

    public static boolean fits(Player player, Pose stance, BodyBox current) {
        var level = player.level();
        var dimensions = player.getDimensions(stance);
        var feet = player.position();
        var frame = GravityFrame.resolve(player);
        if (player instanceof ServerPlayer server) {
            if (server.connection instanceof Connection connection && connection.sableorbits$acceptedFeet() != null)
                feet = connection.sableorbits$acceptedFeet();
            if (frame.artificial()) {
                var local = Spacewalk.contactFeet(server, frame.deck(), feet);
                if (local != null) feet = frame.deck().logicalPose().transformPosition(local);
            }
        }
        var upright = dimensions.makeBoundingBox(feet).deflate(.001);
        var candidate = BodyBox.at(upright, feet, new Quaternionf(current.rotation()),
            current.pivotHeight() == 0 ? 0 : dimensions.eyeHeight());
        var center = candidate.getCenter();
        var scratch = ((LevelExtension)level).sable$getJOMLSink();
        var body = new OrientedBoundingBox3d(center.x, center.y, center.z, upright.getXsize(), upright.getYsize(),
            upright.getZsize(), candidate.rotation(), scratch);
        var obstacle = new OrientedBoundingBox3d(scratch);
        var separation = new Vector3d();
        if (blocked(level.getBlockCollisions(player, candidate), body, obstacle, separation) ||
            blocked(level.getEntityCollisions(player, candidate), body, obstacle, separation)) return false;
        var border = level.getWorldBorder();
        if (border.isInsideCloseToBorder(player, candidate) && (candidate.minX < border.getMinX() ||
            candidate.maxX > border.getMaxX() || candidate.minZ < border.getMinZ() || candidate.maxZ > border.getMaxZ())) return false;

        var support = frame.deck();
        if (support != null && blockedInPlot(player, support, candidate, body, obstacle, separation)) return false;
        for (var plot : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(candidate)))
            if (plot != support && blockedInPlot(player, plot, candidate, body, obstacle, separation)) return false;
        return true;
    }

    private static boolean blockedInPlot(Player player, SubLevel plot, BodyBox candidate, OrientedBoundingBox3d body,
                                         OrientedBoundingBox3d obstacle, Vector3d separation) {
        var pose = plot.logicalPose();
        var center = pose.transformPositionInverse(candidate.getCenter());
        body.getPosition().set(center.x, center.y, center.z);
        body.setOrientation(new Quaterniond(pose.orientation()).conjugate().mul(candidate.rotation()));
        var bounds = new BoundingBox3d(candidate).transformInverse(pose).toMojang();
        return blocked(player.level().getBlockCollisions(player, bounds), body, obstacle, separation);
    }

    private static boolean blocked(Iterable<VoxelShape> shapes, OrientedBoundingBox3d body,
                                   OrientedBoundingBox3d obstacle, Vector3d separation) {
        for (var shape : shapes) for (var box : shape.toAabbs()) {
            var center = box.getCenter();
            obstacle.getPosition().set(center.x, center.y, center.z);
            obstacle.getDimensions().set(box.getXsize(), box.getYsize(), box.getZsize());
            OrientedBoundingBox3d.sat(body, obstacle, separation);
            if (separation.x != Double.MAX_VALUE && separation.lengthSquared() > 1e-12) return true;
        }
        return false;
    }
}
