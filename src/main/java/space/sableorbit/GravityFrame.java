package space.sableorbit;

import com.hbm.inventory.fluid.Fluids;
import com.ntmspace.atmosphere.AtmosphereBlob;
import com.ntmspace.atmosphere.AtmosphereHandler;
import com.ntmspace.orbit.Orbit;
import com.ntmspace.orbit.OrbitEnvironment;
import com.ntmspace.tileentity.TileEntityAirPump;
import com.ntmspace.world.SpaceEnvironment;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** The supporting face is stored in plot axes so clients can follow its interpolated pose. */
public record GravityFrame(SubLevel deck, Direction localUp, boolean artificial, boolean free) {
    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);
    private static final double MIN_WALKABLE_UP = Math.cos(Math.PI / 4) - 1e-6;
    private static final GravityFrame UPRIGHT = new GravityFrame(null, Direction.UP, false, false);
    private static final GravityFrame EVA = new GravityFrame(null, Direction.UP, false, true);
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(
        NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "sableorbits");

    // A negative scalar denotes a planetary floor. Zero denotes disabled station gravity.
    private record Surface(UUID hull, Direction face, float gravity) {}
    private static final Supplier<AttachmentType<Surface>> SURFACE = TYPES.register("supporting_surface", () ->
        AttachmentType.builder(() -> new Surface(new UUID(0, 0), Direction.UP, 0)).sync(StreamCodec.of(
            (buf, s) -> { buf.writeUUID(s.hull); buf.writeEnum(s.face); buf.writeFloat(s.gravity); },
            buf -> new Surface(buf.readUUID(), buf.readEnum(Direction.class), buf.readFloat()))).build());
    private static final Supplier<AttachmentType<Vents>> VENTS = TYPES.register("vent_rooms", () ->
        AttachmentType.builder(Vents::new).build());

    static void register(IEventBus bus) {
        TYPES.register(bus);
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Pre event) -> {
            var entity = event.getEntity();
            if (entity instanceof ServerPlayer && OrbitSpaces.isolated(entity.level())) return;
            if (!entity.level().isClientSide && (entity instanceof LivingEntity || nonliving(entity))) update(entity);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player && OrbitSpaces.isolated(player.level())
                    && !player.isPassenger() && !player.isSpectator() && !player.isSleeping()) {
                // Capture the login position before the first occupied physics tick.
                update(player);
                if (resolve(player).deck() instanceof ServerSubLevel deck) Boarding.followOnLoad(player, deck, player.position(), true);
            }
        });
    }

    public Vec3 up() {
        return deck == null ? WORLD_UP :
            deck.logicalPose().transformNormal(Vec3.atLowerCornerOf(localUp.getNormal())).normalize();
    }

    /** These entities have a freestanding body, and can acquire support independently of a rider. */
    public static boolean nonliving(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof AbstractMinecart || entity instanceof Boat;
    }

    public static GravityFrame resolve(Entity entity) {
        boolean orbit = OrbitSpaces.isolated(entity.level());
        var surface = entity.getExistingDataOrNull(SURFACE);
        if (surface != null && orbit == (surface.gravity >= 0) && surface.gravity != 0) {
            var plots = SubLevelContainer.getContainer(entity.level());
            var deck = plots == null ? null : plots.getSubLevel(surface.hull);
            if (deck != null && !deck.isRemoved()) return new GravityFrame(deck, surface.face, orbit, false);
            // A client can receive entity data before receiving the plot's first snapshot.
            if (orbit) return UPRIGHT;
        }
        return orbit && entity instanceof Player player && !player.isPassenger() && !player.isSpectator() && !player.isSleeping()
            && Sable.HELPER.getContaining(entity) == null ? EVA : UPRIGHT;
    }

    public static float orbitalGravity(Entity entity) {
        var surface = entity.getExistingDataOrNull(SURFACE);
        return surface == null ? 0 : Math.max(0, surface.gravity);
    }

    /** The server owns both room membership and the identity of the floor used during a jump. */
    public static void update(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !(entity instanceof LivingEntity || nonliving(entity))) return;
        var previous = entity.getExistingDataOrNull(SURFACE);
        boolean orbit = OrbitSpaces.isolated(level);
        // Terrain drops without a Sable contact do not need a planetary floor query.
        // In a rolled cabin, Sable may first see floor contact as a horizontal collision.
        boolean unsupported = !(entity instanceof LivingEntity) && (entity.isPassenger()
            || (!orbit && Sable.HELPER.getTrackingSubLevel(entity) == null));
        if (unsupported && previous == null) return;
        var plots = SubLevelContainer.getContainer(level);
        Surface next = null;
        if (!unsupported && plots != null && plots.getLoadedCount() != 0 && Sable.HELPER.getContaining(entity) == null) {
            if (orbit) next = oxygenRoom(entity, level, plots, previous);
            else if (!Orbit.inOrbit(level)) next = floor(entity, plots, previous);
        }
        if (java.util.Objects.equals(previous, next)) return;
        if (next == null) entity.removeData(SURFACE);
        else entity.setData(SURFACE, next);
        if (orbit && entity instanceof LivingEntity living) SpaceEnvironment.measure(living);
    }

    private static final class Vents {
        long tick = Long.MIN_VALUE;
        final Map<SubLevel, List<AtmosphereBlob>> byDeck = new HashMap<>();

        void refresh(ServerLevel level) {
            if (tick == level.getGameTime()) return;
            tick = level.getGameTime();
            byDeck.clear();
            for (var blob : AtmosphereHandler.blobsOf(level)) {
                if (!(blob.provider() instanceof TileEntityAirPump vent) || vent.isRemoved() || !blob.hasFluid(Fluids.OXYGEN)) continue;
                var deck = Sable.HELPER.getContaining(vent);
                if (deck != null) byDeck.computeIfAbsent(deck, unused -> new ArrayList<>()).add(blob);
            }
        }
    }

    private static Surface oxygenRoom(Entity entity, ServerLevel level, SubLevelContainer plots, Surface previous) {
        var vents = level.getData(VENTS);
        vents.refresh(level);
        if (vents.byDeck.isEmpty()) return null;
        var tracking = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
        var feet = entity.position();
        var surface = roomAt(vents, tracking, entity, previous);
        if (surface != null) return surface;
        var priorDeck = previous == null ? null : plots.getSubLevel(previous.hull);
        if (priorDeck != tracking && (surface = roomAt(vents, priorDeck, entity, previous)) != null) return surface;
        // Floating residents need no deck contact. Check nearby plots, then require
        // membership in this particular vent's sealed cells.
        double radius = Math.max(1, entity.getBbHeight());
        var nearby = new BoundingBox3d(feet.x-radius, feet.y-radius, feet.z-radius, feet.x+radius, feet.y+radius, feet.z+radius);
        for (var candidate : plots.queryIntersecting(nearby)) {
            if (candidate != tracking && candidate != priorDeck && (surface = roomAt(vents, candidate, entity, previous)) != null) return surface;
        }
        return null;
    }

    private static Surface roomAt(Vents vents, SubLevel candidate, Entity entity, Surface previous) {
        var rooms = vents.byDeck.get(candidate);
        if (rooms == null || !(candidate instanceof ServerSubLevel deck) || deck.isRemoved()) return null;
        var feet = entity instanceof ServerPlayer player ? Spacewalk.contactFeet(player, deck, player.position()) : null;
        if (feet == null) feet = deck.logicalPose().transformPositionInverse(entity.position());
        var cell = BlockPos.containing(feet.add(0, .025, 0));
        for (var room : rooms) {
            if (!room.hasFluid(Fluids.OXYGEN)) continue;
            if (!room.contains(cell) && (!(entity instanceof LivingEntity) || !bodyInRoom(entity, deck, feet, room))) continue;
            if (!(entity instanceof LivingEntity) && !restingInCabin(entity, deck, previous)) return null;
            var station = Stations.nativeStation(deck);
            float gravity = OrbitEnvironment.STANDARD_GRAVITY * (station == null ? 1 : station.gravityMultiplier);
            return new Surface(deck.getUniqueId(), Direction.UP, gravity < .2f ? 0 : gravity);
        }
        return null;
    }

    private static boolean bodyInRoom(Entity entity, SubLevel deck, Vec3 feet, AtmosphereBlob room) {
        // A partial floor or wall can seal its entire cell while leaving space for
        // the player inside it. Test an inscribed sphere, which stays inside the
        // actual collider even when a swimming or deck-aligned body is rotated.
        var offset = entity.getBoundingBox().getCenter().subtract(entity.position());
        var center = feet.add(deck.logicalPose().transformNormalInverse(offset));
        double radius = Math.min(.5, Math.min(entity.getBbWidth(), entity.getBbHeight()) * .5) - 1e-5;
        if (radius <= 0) return false;
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(center.subtract(radius, radius, radius)),
                BlockPos.containing(center.add(radius, radius, radius)))) {
            if (!room.contains(pos)) continue;
            double x = Stations.gap(center.x, pos.getX(), pos.getX()+1);
            double y = Stations.gap(center.y, pos.getY(), pos.getY()+1);
            double z = Stations.gap(center.z, pos.getZ(), pos.getZ()+1);
            if (x*x + y*y + z*z < radius*radius) return true;
        }
        return false;
    }

    private static boolean restingInCabin(Entity entity, SubLevel deck, Surface previous) {
        var up = deck.logicalPose().transformNormal(WORLD_UP).normalize();
        double reach = .025;
        // Before alignment a vertical deck meets the side of the upright object. Allow
        // gravity to close that original gap as it settles, but release an outgoing body.
        if (!(entity.getBoundingBox() instanceof BodyBox) || (previous != null && previous.hull.equals(deck.getUniqueId())
            && entity.getDeltaMovement().dot(up) <= 0)) {
            reach += (Math.abs(up.x) + Math.abs(up.z)) * entity.getBbWidth() * .5 + Math.max(0, -up.y) * entity.getBbHeight();
        }
        return supportBelow(entity, deck, up, reach) == Direction.UP;
    }

    private static Surface floor(Entity entity, SubLevelContainer plots, Surface previous) {
        if (entity.isPassenger()) return null;
        var tracking = Sable.HELPER.getTrackingSubLevel(entity);
        if (tracking != null && !tracking.isRemoved() && entity.onGround()) {
            var contact = ((EntityMovementExtension)entity).sable$getCollisionInfo();
            var first = contact == null || contact.firstCollisions == null ? null : contact.firstCollisions.get(tracking);
            Direction face = null;
            if (first != null && first.globalDirection().y() > .05 && !first.horizontal()) {
                var local = tracking.logicalPose().transformNormalInverse(first.globalDirection(), new org.joml.Vector3d());
                face = Direction.getNearest(local.x, local.y, local.z);
            }
            // Server players don't run Sable's manifold solver. Sample the actual collision
            // shape below the reported feet, including when a rolled ship rests on its side.
            if (face == null) face = supportBelow(entity, tracking, WORLD_UP, Math.min(.8, entity.getBbWidth()*.5+.2));
            if (face != null) return walkable(entity, tracking, face) ? new Surface(tracking.getUniqueId(), face, -1) : null;
        }
        if (previous == null || previous.gravity >= 0 || !(entity instanceof LivingEntity) || (tracking == null && entity.onGround())) return null;
        var deck = plots.getSubLevel(previous.hull);
        if (deck == null || deck.isRemoved() || (tracking != null && tracking != deck) || !walkable(entity, deck, previous.face)) return null;
        var p = deck.logicalPose().transformPositionInverse(entity.position());
        var b = deck.getPlot().getBoundingBox();
        // Keep the departing face through a normal jump above an exposed platform. Only
        // the departing face gets headroom: stepping off the side returns to world-up.
        var hull = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX()+1, b.maxY()+1, b.maxZ()+1);
        var headroom = Vec3.atLowerCornerOf(previous.face.getNormal()).scale(2);
        return hull.inflate(entity.getBbWidth() * .5).expandTowards(headroom).contains(p) ? previous : null;
    }

    private static boolean walkable(Entity entity, SubLevel deck, Direction face) {
        return !(entity instanceof Player) ||
            deck.logicalPose().transformNormal(Vec3.atLowerCornerOf(face.getNormal())).y >= MIN_WALKABLE_UP;
    }

    private static Direction supportBelow(Entity entity, SubLevel deck, Vec3 up, double below) {
        var pose = deck.logicalPose();
        var feet = entity.position();
        var start = pose.transformPositionInverse(feet.add(up.scale(.08)));
        var end = pose.transformPositionInverse(feet.subtract(up.scale(below)));
        var low = BlockPos.containing(Math.min(start.x,end.x),Math.min(start.y,end.y)-1,Math.min(start.z,end.z));
        var high = BlockPos.containing(Math.max(start.x,end.x),Math.max(start.y,end.y),Math.max(start.z,end.z));
        double nearest = Double.POSITIVE_INFINITY;
        var context = CollisionContext.of(entity);
        Direction face = null;
        for (var pos : BlockPos.betweenClosed(low, high)) {
            if (!deck.getPlot().contains(new ChunkPos(pos))) continue;
            var chunk = entity.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) continue;
            var shape = chunk.getBlockState(pos).getCollisionShape(entity.level(), pos, context);
            var hit = shape.clip(start, end, pos);
            if (hit != null && hit.getLocation().distanceToSqr(start) < nearest) {
                nearest = hit.getLocation().distanceToSqr(start);
                face = hit.getDirection();
            }
        }
        return face;
    }
}
