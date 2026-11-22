package space.sableorbit;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Crouch ledge protection on a floor whose normal is defined in plot coordinates. */
public final class DeckEdge {
    public static Vec3 backOff(Player player, Vec3 movement, GravityFrame frame) {
        var plot = frame.deck().logicalPose();
        var local = plot.transformNormalInverse(movement);
        var face = frame.localUp();
        var normalAxis = face.getAxis();
        double sign = face.getAxisDirection().getStep();
        var tangent = local.with(normalAxis, 0);
        if (tangent.lengthSqr() < 1e-14 || local.get(normalAxis) * sign > 1e-5) return movement;

        var feet = plot.transformPositionInverse(player.position());
        // Inset the support patch inside the rotated player's sole so a sliver of the
        // broad-phase world box beyond a corner cannot be mistaken for footing.
        double width = player.getBbWidth() * .65;
        var patchSize = new Vec3(width, width, width).with(normalAxis, 0);
        var probe = AABB.ofSize(feet, patchSize.x, patchSize.y, patchSize.z)
            .expandTowards(Vec3.ZERO.with(normalAxis, -sign * (player.maxUpStep() + .001)))
            .expandTowards(Vec3.ZERO.with(normalAxis, sign * .025));
        if (!supported(player, probe, face) || supported(player, probe.move(tangent), face)) return movement;

        // As in vanilla, allow travel along the lip even when the outward component stops.
        var axis = normalAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        var first = Vec3.ZERO.with(axis, tangent.get(axis));
        var second = tangent.with(axis, 0);
        var safe = shorten(player, probe, face, first).add(shorten(player, probe, face, second));
        safe = shorten(player, probe, face, safe);
        return plot.transformNormal(safe.with(normalAxis, local.get(normalAxis)));
    }

    private static Vec3 shorten(Player player, AABB probe, Direction face, Vec3 delta) {
        if (delta.lengthSqr() < 1e-14 || supported(player, probe.move(delta), face)) return delta;
        double low = 0, high = 1;
        for (int i = 0; i < 8; i++) {
            double fraction = (low + high) * .5;
            if (supported(player, probe.move(delta.scale(fraction)), face)) low = fraction;
            else high = fraction;
        }
        return delta.scale(low);
    }

    private static boolean supported(Player player, AABB probe, Direction face) {
        var axis = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        for (var shape : player.level().getBlockCollisions(player, probe)) {
            for (var part : shape.toAabbs()) {
                if (part.intersects(probe) && (positive ? part.max(axis) <= probe.max(axis) : part.min(axis) >= probe.min(axis))) return true;
            }
        }
        return false;
    }
}
