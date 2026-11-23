package space.sableorbit;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelTrackingPlugin;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class Spacewalk {
    public interface Travel {
        boolean sableorbits$takeDeckStep();
    }

    // Sable snapshots and the client's interpolation pointer share this dimension-local clock.
    static final int HISTORY_TICKS = 32;
    static int clock(ServerPlayer player) {
        return dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(player.serverLevel()).trackingSystem().getInterpolationTick();
    }

    record Contact(int time, Vec3 world, Vec3 velocity) {
        Vec3 at(double t) { return world.add(velocity.scale(t-time)); }
        Vec3 relativeDisplacement(Vec3 displacement, double elapsed) {
            return displacement.subtract(velocity.scale(elapsed));
        }
    }

    static final class Reference {
        final UUID hull;
        final net.minecraft.world.level.Level level;
        SubLevel deck;
        final ArrayDeque<Contact> history = new ArrayDeque<>();
        Contact departure;
        double releaseTime;
        double lastDisplay = Double.NEGATIVE_INFINITY;
        int lastContactPacket, positionClock;
        Vec3 feet, world;
        Vec3 offset = Vec3.ZERO, firstOffset = Vec3.ZERO;
        Vec3 steeringVelocity = Vec3.ZERO;
        int validationTick = -1, lastMove;

        Reference(SubLevel deck) {
            this.deck = deck;
            hull = deck.getUniqueId();
            level = deck.getLevel();
        }

        void sample(int time, Vec3 position) {
            var velocity = Sable.HELPER.getVelocity(level, deck, feet).scale(0.05);
            if (!Double.isFinite(velocity.lengthSqr())) return;
            if (!history.isEmpty() && history.getLast().time == time) history.removeLast();
            history.addLast(new Contact(time,position,velocity));
            while (history.size() > HISTORY_TICKS) history.removeFirst();
        }

        Contact contactAt(double time, double displayed, int now, int latencyMs) {
            // A fractional client tick can straddle the current server tick boundary.
            if (!Double.isFinite(time) || !Double.isFinite(displayed) || displayed < time || displayed < lastDisplay
                    || displayed < now-HISTORY_TICKS || displayed > now+1.0) return null;
            if (departure != null) return releaseTime == time ? departure : null;
            if (history.isEmpty()) return null;
            // Native grounded packets do not carry timestamps. Allow interpolation plus the
            // measured network delay, bounded by the history we can independently verify.
            double tolerance = Math.min(HISTORY_TICKS, Math.max(5.0 + latencyMs / 50.0, now-displayed+2));
            if (time < lastDisplay || time < lastContactPacket-tolerance
                    || time < history.getFirst().time-tolerance || time > history.getLast().time+tolerance
                    || now-time > HISTORY_TICKS) return null;
            Contact closest = history.getLast();
            for (var sample : history) {
                if (Math.abs(sample.time-time) < Math.abs(closest.time-time)) closest = sample;
            }
            return closest;
        }

        boolean reachableAtRelease(Contact launch, Vec3 target, Vec3 playerWorld, int now) {
            // The server may have followed the deck until this packet arrived. Only real
            // changes in hull velocity can separate that path from the launch tangent.
            double maneuver = 0;
            for (var sample : history) {
                if (sample.time >= launch.time)
                    maneuver = Math.max(maneuver, sample.velocity.distanceTo(launch.velocity));
            }
            double separation = maneuver * Math.max(1, now-launch.time+1);
            double inTransit = launch.velocity.length() * Math.max(2, now-positionClock+2);
            double reach = 10 + separation + inTransit;
            return target.distanceToSqr(playerWorld) <= reach*reach;
        }
        Vec3 startOfTick(int tick) {
            if (validationTick != tick) {
                validationTick = tick;
                firstOffset = offset;
            }
            return firstOffset;
        }
    }
    private static final Map<ServerPlayer,Reference> frames = new WeakHashMap<>();

    public static void forget(ServerPlayer player) { frames.remove(player); }

    static void restore(ServerPlayer player, Reference reference) {
        if (reference != null) frames.put(player, reference);
    }

    public static boolean rotationOnly(Set<RelativeMovement> relative, double dx, double dy, double dz) {
        return dx == 0 && dy == 0 && dz == 0 && relative.contains(RelativeMovement.X)
            && relative.contains(RelativeMovement.Y) && relative.contains(RelativeMovement.Z);
    }

    static void remember(ServerPlayer player, SubLevel deck) {
        var ref = frames.get(player);
        if (ref == null || ref.deck != deck) {
            double lastDisplay = ref != null && ref.level == deck.getLevel() ? ref.lastDisplay : Double.NEGATIVE_INFINITY;
            ref = new Reference(deck);
            ref.lastDisplay = lastDisplay;
            frames.put(player,ref);
        }
        ref.feet = deck.logicalPose().transformPositionInverse(player.position());
        ref.world = player.position();
        ref.lastMove = player.tickCount;
        ref.positionClock = ref.lastContactPacket = clock(player);
        ref.sample(ref.positionClock,ref.world);
    }

    public static void observeContact(ServerPlayer player) {
        if (!OrbitSpaces.isolated(player.level())) return;
        var deck = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (deck != null && !deck.isRemoved() && distanceSquared(deck,player.position()) < 16*16) remember(player,deck);
    }

    static Reference reference(ServerPlayer player) {
        var ref = frames.get(player);
        if (ref != null && (ref.level != player.level() || player.tickCount-ref.lastMove > 60)) {
            forget(player);
            return null;
        }
        return ref;
    }

    // A grounded packet's world position belongs to the hull pose at acceptance.
    // Until another move is accepted, use its plot coordinates for cabin queries.
    static Vec3 contactFeet(ServerPlayer player, SubLevel deck, Vec3 accepted) {
        var boarding = Boarding.pendingFeet(player, deck);
        if (boarding != null) return boarding;
        if (Sable.HELPER.getTrackingSubLevel(player) != deck) return null;
        var ref = reference(player);
        return ref != null && ref.deck == deck && accepted.equals(ref.world) ? ref.feet : null;
    }

    static void tick(ServerPlayer player) {
        if (player.isPassenger()) { observeContact(player); return; }
        var ref = reference(player);
        if (ref == null) return;
        int now = clock(player);
        boolean atLastPosition = player.position().distanceToSqr(ref.world) < 0.001;
        if (ref.departure != null) {
            if (!atLastPosition) return;
            ref.world = ref.departure.at(now).add(ref.offset);
        } else if (!ref.deck.isRemoved() && Sable.HELPER.getTrackingSubLevel(player) == ref.deck) {
            var contact = ref.deck.logicalPose().transformPosition(ref.feet);
            if (!atLastPosition) {
                // Sable may already have carried a standing rider this tick.
                if (player.position().distanceToSqr(contact) > 1) return;
                contact = player.position();
                ref.feet = ref.deck.logicalPose().transformPositionInverse(contact);
            }
            ref.world = contact;
            ref.sample(now,contact);
        } else return;
        ref.positionClock = now;
        player.setPos(ref.world);
    }
    public static void trackInterpolation(ServerLevel level, SubLevelTrackingSystem tracker) {
        if (!OrbitSpaces.isolated(level)) return;
        tracker.addTrackingPlugin(new SubLevelTrackingPlugin() {
            private final List<UUID> pilots = new ArrayList<>();

            public Iterable<UUID> neededPlayers() {
                // Sable stops its clock when no moving plot is visible. A pilot still
                // needs simulation timestamps for takeoff from rest and for free flight.
                pilots.clear();
                for (var player : level.players()) {
                    if (reference(player) != null) pilots.add(player.getUUID());
                }
                return pilots;
            }

            public void sendTrackingData(int tick) {} // Sable sends the clock itself.
        });
    }

    public static Vec3 translation(SubLevel deck) {
        var pivot = deck.logicalPose().rotationPoint();
        var center = new Vec3(pivot.x(),pivot.y(),pivot.z());
        return deck.logicalPose().transformPosition(center).subtract(deck.lastPose().transformPosition(center));
    }

    public static double distanceSquared(SubLevel part, Vec3 point) {
        var b = part.boundingBox();
        double x = Stations.gap(point.x,b.minX(),b.maxX()), y = Stations.gap(point.y,b.minY(),b.maxY()), z = Stations.gap(point.z,b.minZ(),b.maxZ());
        return x*x + y*y + z*z;
    }
    public static boolean drifting(Entity e) {
        return e instanceof Player p && OrbitSpaces.isolated(p.level()) && !p.isPassenger() && !p.onGround()
            && !p.isSpectator() && !p.isInWaterOrBubble() && !p.isInLava() && !p.onClimbable() && !p.isFallFlying()
            && GravityFrame.resolve(p).free();
    }
}
