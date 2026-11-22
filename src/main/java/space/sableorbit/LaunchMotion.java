package space.sableorbit;

import com.hbm.entity.projectile.*;
import com.ntmspace.orbit.Orbit;
import com.ntmspace.world.CelestialBodies;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.*;
import java.util.function.Supplier;

public final class LaunchMotion {
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
        double radius = 1.5;
        double closest = radius * radius;
        SubLevel source = null;
        var plots = SubLevelContainer.getContainer(level);
        if (plots == null) return null;
        for (var hull : plots.getAllSubLevels()) {
            var local = hull.logicalPose().transformPositionInverse(position);
            // At high speed the spatial index can still describe the preceding pose.
            // Test this emission in the current pose, using the plot's occupied bounds.
            var bounds = hull.getPlot().getBoundingBox();
            if (local.x < bounds.minX()-radius || local.x > bounds.maxX()+1+radius
                    || local.y < bounds.minY()-radius || local.y > bounds.maxY()+1+radius
                    || local.z < bounds.minZ()-radius || local.z > bounds.maxZ()+1+radius) continue;
            var first = BlockPos.containing(local.x-radius, local.y-radius, local.z-radius);
            var last = BlockPos.containing(local.x+radius, local.y+radius, local.z+radius);
            for (var block : BlockPos.betweenClosed(first, last)) {
                double distance = new AABB(block).distanceToSqr(local);
                if (distance >= closest) continue;
                var chunk = level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4);
                if (chunk != null && !chunk.getBlockState(block).isAir()) {
                    closest = distance;
                    source = hull;
                    if (distance == 0) return hull;
                }
            }
        }
        return source;
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
