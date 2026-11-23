package space.sableorbit;

import com.hbm.entity.projectile.*;
import com.ntmspace.orbit.Orbit;
import com.ntmspace.world.CelestialBodies;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.*;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.function.Supplier;

public final class LaunchMotion {
    private static final double SOURCE_RADIUS = 1.5;
    static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "sableorbits");
    // Sedna advances a heading by a configurable distance per tick. Keep the launch
    // platform's displacement separate from that heading, including across saves.
    public static final Supplier<AttachmentType<Vec3>> DRIFT = TYPES.register("launch_velocity", () ->
        AttachmentType.builder(() -> Vec3.ZERO).serialize(Vec3.CODEC).sync(StreamCodec.of(
            (buf,v) -> buf.writeVec3(v), buf -> buf.readVec3())).build());

    public static boolean ballistic(Entity e) {
        return e instanceof Projectile || e instanceof ItemEntity || e instanceof EntityThrowableNT
            || e instanceof EntityBullet || e instanceof EntityBulletBaseMK4 || e.getType().is(EntityTypeTags.IMPACT_PROJECTILES);
    }

    public static double vacuumDrag(Entity entity,double normal) {
        return Orbit.inOrbit(entity.level()) && !entity.isInWater() && !entity.isInLava() ? 1 : normal;
    }

    public static double gravity(Entity e) {
        if (Orbit.inOrbit(e.level())) return 0;
        var body = CelestialBodies.of(e.level());
        return body == null || body == CelestialBodies.KERBIN ? 1 : body.surfaceGravity() / CelestialBodies.KERBIN.surfaceGravity();
    }

    public static Vec3 carrier(Entity owner, Vec3 worldPoint) {
        if (owner == null || !Orbit.inOrbit(owner.level())) return Vec3.ZERO;
        var deck = Sable.HELPER.getTrackingOrVehicleSubLevel(owner);
        if (owner instanceof ServerPlayer player) {
            var ref = Spacewalk.reference(player);
            if (ref != null && ref.departure != null && deck == null) return ref.departure.velocity().add(ref.steeringVelocity);
            if (deck == null && ref != null && Boarding.pending(player)) deck = ref.deck;
        }
        return deck == null ? Vec3.ZERO : at(deck, worldPoint);
    }

    public static Vec3 at(SubLevel deck, Vec3 worldPoint) {
        return Sable.HELPER.getVelocity(deck.getLevel(),deck,deck.logicalPose().transformPositionInverse(worldPoint)).scale(.05);
    }

    // Some dispensers and particle providers project their outlet into world coordinates
    // before spawning. Only attribute an anonymous emission to a nearby occupied block.
    static SubLevel nearbyHull(Level level, Vec3 position) {
        var plots = SubLevelContainer.getContainer(level);
        if (plots == null || plots.getLoadedCount() == 0) return null;
        var worldPoint = new Vector3d(position.x, position.y, position.z);
        var local = new Vector3d();
        double closest = SOURCE_RADIUS * SOURCE_RADIUS;
        SubLevel source = null;
        for (var hull : plots.getAllSubLevels()) {
            var pose = hull.logicalPose();
            var bounds = hull.getPlot().getBoundingBox();
            if (outsideHullReach(pose, bounds, worldPoint)) continue;
            pose.transformPositionInverse(worldPoint, local);
            if (local.x < bounds.minX() - SOURCE_RADIUS || local.x > bounds.maxX() + 1 + SOURCE_RADIUS
                    || local.y < bounds.minY() - SOURCE_RADIUS || local.y > bounds.maxY() + 1 + SOURCE_RADIUS
                    || local.z < bounds.minZ() - SOURCE_RADIUS || local.z > bounds.maxZ() + 1 + SOURCE_RADIUS) continue;

            double distance = nearestOccupiedDistance(level, local, closest);
            if (distance < closest) {
                source = hull;
                closest = distance;
                if (distance == 0) return hull;
            }
        }
        return source;
    }

    private static boolean outsideHullReach(Pose3dc pose, BoundingBox3ic bounds, Vector3dc worldPoint) {
        // A sphere about the current pivot encloses the expanded occupied bounds at any rotation.
        // Sable's cached world bounds may still describe the preceding pose during emission.
        var pivot = pose.rotationPoint();
        var scale = pose.scale();
        double x = reach(bounds.minX(), bounds.maxX(), pivot.x(), scale.x());
        double y = reach(bounds.minY(), bounds.maxY(), pivot.y(), scale.y());
        double z = reach(bounds.minZ(), bounds.maxZ(), pivot.z(), scale.z());
        return pose.position().distanceSquared(worldPoint) > x*x + y*y + z*z;
    }

    private static double reach(int min, int max, double pivot, double scale) {
        return (Math.max(Math.abs(min - pivot), Math.abs(max + 1.0 - pivot)) + SOURCE_RADIUS) * Math.abs(scale) + 1e-6;
    }

    private static double nearestOccupiedDistance(Level level, Vector3dc local, double closest) {
        int minX = Mth.floor(local.x() - SOURCE_RADIUS), maxX = Mth.floor(local.x() + SOURCE_RADIUS);
        int minY = Mth.floor(local.y() - SOURCE_RADIUS), maxY = Mth.floor(local.y() + SOURCE_RADIUS);
        int minZ = Mth.floor(local.z() - SOURCE_RADIUS), maxZ = Mth.floor(local.z() + SOURCE_RADIUS);
        var chunks = level.getChunkSource();
        for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
            for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
                var chunk = chunks.getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                int startX = Math.max(minX, chunkX << 4), endX = Math.min(maxX, (chunkX << 4) + 15);
                int startZ = Math.max(minZ, chunkZ << 4), endZ = Math.min(maxZ, (chunkZ << 4) + 15);
                for (var block : BlockPos.betweenClosed(startX, minY, startZ, endX, maxY, endZ)) {
                    double x = Stations.gap(local.x(), block.getX(), block.getX() + 1.0);
                    double y = Stations.gap(local.y(), block.getY(), block.getY() + 1.0);
                    double z = Stations.gap(local.z(), block.getZ(), block.getZ() + 1.0);
                    double distance = x*x + y*y + z*z;
                    if (distance >= closest || chunk.getBlockState(block).isAir()) continue;
                    closest = distance;
                    if (distance == 0) return 0;
                }
            }
        }
        return closest;
    }

    private static Entity owner(Entity entity) {
        if (entity instanceof TraceableEntity traceable) return traceable.getOwner();
        if (entity instanceof EntityBullet legacy) return legacy.shootingEntity;
        if (entity instanceof EntityBulletBaseMK4 bullet) return bullet.getThrower();
        if (entity instanceof EntityThrowableNT thrown) return thrown.getThrower();
        return null;
    }

    public static void launch(Entity e) {
        if (!ballistic(e) || !Orbit.inOrbit(e.level()) || e.hasData(DRIFT)) return;
        var plot = Sable.HELPER.getContaining(e);
        Vec3 drift;
        if (plot != null) {
            if (!EntitySubLevelUtil.shouldKick(e)) return;
            drift = Sable.HELPER.getVelocity(e.level(),plot,e.position()).scale(.05);
            // Sable rotates the delta when it projects the entity, and supplies carry
            // itself for a fireball whose acceleration has been disabled.
            boolean nativeCarry = e instanceof AbstractHurtingProjectile fireball && fireball.accelerationPower == 0;
            if (!(e instanceof EntityBulletBaseMK4) && !nativeCarry) {
                e.setDeltaMovement(e.getDeltaMovement().add(plot.logicalPose().transformNormalInverse(drift)));
            }
        } else {
            var owner = owner(e);
            var outlet = owner == null ? nearbyHull(e.level(), e.position()) : null;
            drift = outlet != null ? at(outlet, e.position()) : carrier(owner, e.position());
            if (!(e instanceof EntityBulletBaseMK4)) e.setDeltaMovement(e.getDeltaMovement().add(drift));
        }
        if (drift.lengthSqr() > 0) e.setData(DRIFT,drift);
    }
}
