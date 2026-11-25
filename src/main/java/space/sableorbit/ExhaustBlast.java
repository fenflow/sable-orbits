package space.sableorbit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

final class ExhaustBlast {
    static void apply(ServerLevel level, EngineTile engine, SubLevel source, double impulse) {
        var bounds = engine.bounds();
        var facing = engine.nozzle();
        var axis = Vec3.atLowerCornerOf(facing.getNormal());
        double length = facing.getAxis().choose(bounds.getXsize(), bounds.getYsize(), bounds.getZsize());
        var origin = bounds.getCenter().add(axis.scale(length * .5 + .015));
        var side = Vec3.atLowerCornerOf((facing.getAxis().isVertical() ? Direction.EAST : facing.getClockWise()).getNormal());
        if (source != null) {
            var pose = source.logicalPose();
            origin = pose.transformPosition(origin);
            axis = pose.transformNormal(axis);
            side = pose.transformNormal(side);
        }
        var up = axis.cross(side);
        double width = Math.max(1, Math.max(engine.engine().bottomRadius(), engine.engine().topRadius()));
        double range = 5 + 2 * width;
        var end = origin.add(axis.scale(range));
        double radius = width * .5;
        var region = new AABB(origin,end).inflate(radius * (Math.abs(side.x) + Math.abs(up.x)),
            radius * (Math.abs(side.y) + Math.abs(up.y)), radius * (Math.abs(side.z) + Math.abs(up.z)));
        try (var batch = new ExhaustClipBatch(level, new BoundingBox3d(region))) {
            for (var entity : level.getEntities((Entity)null, region, e -> !e.isSpectator())) {
                var box = entity.getBoundingBox();
                var relative = box.getCenter().subtract(origin);
                var start = origin.add(side.scale(Math.clamp(relative.dot(side), -radius, radius)))
                    .add(up.scale(Math.clamp(relative.dot(up), -radius, radius)));
                var entry = box.contains(start) ? start : box.clip(start, start.add(axis.scale(range))).orElse(null);
                if (entry == null || clip(level, start, entry).getType() == HitResult.Type.BLOCK) continue;
                entity.igniteForSeconds(4);
                var push = axis.scale(.45 * (1 - .65 * start.distanceTo(entry) / range));
                entity.push(push.x, push.y, push.z);
                entity.hurtMarked = true;
            }
            // Each stream transfers its share at the first solid surface it strikes.
            // This keeps the total bounded even when the exhaust spans several hulls.
            if (batch.isEmpty()) return;
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                var start = origin.add(side.scale(i * width / 3)).add(up.scale(j * width / 3));
                var hit = clip(level, start, start.add(axis.scale(range)));
                if (hit.getType() != HitResult.Type.BLOCK) continue;
                var target = Sable.HELPER.getContaining(level, hit.getBlockPos());
                if (!(target instanceof ServerSubLevel hull)) continue;
                // Sable's projected clip returns the contact in the hit plot's storage coordinates.
                var pose = hull.logicalPose();
                double distance = start.distanceTo(pose.transformPosition(hit.getLocation()));
                var localImpulse = pose.transformNormalInverse(axis).scale(impulse / 9 * (1 - .65 * distance / range));
                var handle = RigidBodyHandle.of(hull);
                if (handle != null && handle.isValid()) handle.applyImpulseAtPoint(hit.getLocation(), localImpulse);
            }
        }
    }

    private static BlockHitResult clip(ServerLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
    }
}
