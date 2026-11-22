package space.sableorbit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import space.sableorbit.mixin.InterpolatorAccess;
import java.util.UUID;

public final class DeckFrame {
    // Both vectors are world-axis displacements used by Sable's collision substeps.
    public static final class Step {
        public final Vec3 translation, relativeCarry;
        private final SubLevel previousDeck;
        private final Vector3d probe = new Vector3d();
        private boolean touching;

        Step(Vec3 translation, Vec3 relativeCarry, SubLevel previousDeck) {
            this.translation = translation;
            this.relativeCarry = relativeCarry;
            this.previousDeck = previousDeck;
        }

        public boolean leftDeck(SubLevel candidate) {
            return candidate != null && candidate == previousDeck && !touching;
        }

        public void checkContact(SubLevel deck, OrientedBoundingBox3d player, OrientedBoundingBox3d block, Vector3d separation) {
            if (deck != previousDeck || touching) return;
            if (separation.lengthSquared() > 0 && separation.x != Double.MAX_VALUE) {
                touching = true;
                return;
            }
            // At rest in zero gravity, two touching faces have no penetration or support
            // impulse. Probe their exact oriented shapes with a milliblock of tolerance.
            var size = player.getDimensions();
            double x = size.x, y = size.y, z = size.z;
            try {
                size.set(x + 0.002, y + 0.002, z + 0.002);
                OrientedBoundingBox3d.sat(player, block, probe);
                touching = probe.lengthSquared() > 0 && probe.x != Double.MAX_VALUE;
            } finally {
                size.set(x, y, z);
            }
        }
    }

    private static LocalPlayer pilot;
    private static UUID hull;
    private static Vec3 origin;
    private static Vec3 velocityPerTick;
    private static double releaseTime;

    public static UUID departureHull(LocalPlayer player) { return pilot == player ? hull : null; }
    public static UUID movingDepartureHull(LocalPlayer player) {
        return pilot == player && hull != null && velocityPerTick.lengthSqr() > 1e-16 ? hull : null;
    }
    public static Vec3 departurePosition(LocalPlayer player) { return origin; }
    public static double departureTime(LocalPlayer player) { return releaseTime; }

    public static Step step(LocalPlayer player) {
        if (pilot != player) clear();
        if (!OrbitSpaces.isolated(player.level()) || player.isSpectator()) {
            clear();
            return null;
        }
        if (player.isPassenger()) return null;
        var container = SubLevelContainer.getContainer((ClientLevel)player.level());
        if (container == null) return null;
        var clock = container.getInterpolation();
        var contact = Sable.HELPER.getTrackingSubLevel(player);
        if (contact instanceof ClientSubLevel deck) {
            if (deck.lastPose().rotationPoint().lengthSquared() == 0) {
                clear();
                return null;
            }
            var local = deck.lastPose().transformPositionInverse(player.position());
            var pointStep = deck.logicalPose().transformPosition(local).subtract(player.position());
            var translation = Spacewalk.translation(deck);
            // This is the departure sample if the native collision solve releases the player.
            remember(player, deck, local, player.position(), deck.lastPose(), clock.lastInterpolationTick, clock);
            return new Step(translation, pointStep.subtract(translation), deck);
        }
        if (hull == null) return null;

        // Each hull uses this dimension-wide clock to display server ticks. Advance the
        // release velocity on that same clock, even when the departed hull has unloaded.
        double elapsed = clock.mostRecentInterpolationTick - clock.lastInterpolationTick;
        var displacement = velocityPerTick.scale(elapsed);
        return new Step(displacement, Vec3.ZERO, null);
    }

    private static void remember(LocalPlayer player, ClientSubLevel deck, Vec3 local, Vec3 world,
                                 Pose3dc pose, double time, ClientSableInterpolationState clock) {
        pilot = player;
        hull = deck.getUniqueId();
        origin = world;
        releaseTime = time;
        var center = pose.position();
        var radius = world.subtract(center.x(), center.y(), center.z());
        velocityPerTick = pointVelocity(deck, local, radius, time, clock);
    }

    static Vec3 freeVelocity(LocalPlayer player) {
        return departureHull(player)==null ? Vec3.ZERO : velocityPerTick.add(player.getDeltaMovement());
    }

    static Vec3 velocityAt(ClientSubLevel deck, Vec3 world) {
        var clock=SubLevelContainer.getContainer(deck.getLevel()).getInterpolation();
        var pose=deck.logicalPose();
        var center=pose.position();
        return pointVelocity(deck,pose.transformPositionInverse(world),world.subtract(center.x(),center.y(),center.z()),clock.mostRecentInterpolationTick,clock);
    }

    private static Vec3 pointVelocity(ClientSubLevel deck, Vec3 local, Vec3 radius, double time, ClientSableInterpolationState clock) {
        var interpolator = deck.getInterpolator();
        var buffer = interpolator.buffer;
        synchronized (buffer) {
            // After the last movement snapshot, Sable holds a stopped deck at its final pose.
            if (((InterpolatorAccess)interpolator).sableorbits$stopped()
                    && (buffer.isEmpty() || time >= buffer.getLast().gameTick())) return Vec3.ZERO;
            for (int i = 1; i < buffer.size(); i++) {
                var next = buffer.get(i);
                if (next.gameTick() < time && i + 1 < buffer.size()) continue;
                var previous = buffer.get(i - 1);
                double ticks = (double)next.gameTick() - previous.gameTick();
                if (ticks <= 0) continue;
                var first = previous.pose();
                var last = next.pose();
                var linear = last.position().sub(first.position(), new Vector3d());
                // A world-axis quaternion delta gives the tangent at the actual release point,
                // including for hulls rotating about an inclined axis.
                var rotation = first.orientation().conjugate(new Quaterniond()).premul(last.orientation()).normalize();
                var axis = new Vec3(rotation.x, rotation.y, rotation.z);
                double length = axis.length();
                double angle = 2 * Math.atan2(length, Math.abs(rotation.w));
                var tangent = length < 1.0e-12 ? Vec3.ZERO :
                    axis.scale(Math.copySign(angle, rotation.w) / length).cross(radius);
                return tangent.add(linear.x, linear.y, linear.z).scale(1 / ticks);
            }
        }
        // A newly tracked plot can have an initial pose before its first movement packet.
        double elapsed = clock.mostRecentInterpolationTick - clock.lastInterpolationTick;
        if (elapsed <= 0 || deck.lastPose().rotationPoint().lengthSquared() == 0) return Vec3.ZERO;
        return deck.logicalPose().transformPosition(local).subtract(deck.lastPose().transformPosition(local)).scale(1 / elapsed);
    }

    static void rememberRide(LocalPlayer player) {
        if (player != null && player.isPassenger() && OrbitSpaces.isolated(player.level())) {
            var deck = Sable.HELPER.getVehicleSubLevel(player);
            if (deck != null) board(player,deck);
        }
    }

    public static void clear() {
        pilot = null;
        hull = null;
        origin = velocityPerTick = Vec3.ZERO;
    }

    public static void corrected(LocalPlayer player) {
        if (player != null && pilot == player && hull != null && OrbitSpaces.isolated(player.level())
                && !player.isPassenger() && Sable.HELPER.getTrackingSubLevel(player) == null) {
            // Native travel keeps a separate carry vector. A server correction ends this
            // departure, so its last step must not become ordinary unframed movement.
            ((LivingEntityMovementExtension)player).sable$getInheritedVelocity().zero();
            var movement = (EntityMovementExtension)player;
            movement.sable$setLastTrackingSubLevelID(null);
            var collision = movement.sable$getCollisionInfo();
            if (collision != null) collision.inheritedMotion = null;
        }
        clear();
    }

    public static void board(LocalPlayer player, SubLevel deck) {
        if (!(deck instanceof ClientSubLevel clientDeck)) return;
        var container = SubLevelContainer.getContainer(clientDeck.getLevel());
        if (container == null) return;
        var clock = container.getInterpolation();
        var world = Sable.HELPER.projectOutOfSubLevel(player.level(),player.position());
        var local = deck.logicalPose().transformPositionInverse(world);
        remember(player, clientDeck, local, world, deck.logicalPose(), clock.mostRecentInterpolationTick, clock);
    }
}
