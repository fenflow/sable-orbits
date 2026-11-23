package space.sableorbit;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Scopes event overrides to restoring the exact state held before a failed transfer. */
public final class FlightRollback {
    private FlightRollback() {}

    private record Travel(ServerPlayer player, ServerLevel origin) {}
    private record Riding(Entity rider, Entity mount, boolean mounting) {}

    private static final ThreadLocal<Travel> TRAVEL = new ThreadLocal<>();
    private static final ThreadLocal<Riding> RIDING = new ThreadLocal<>();

    static void returnPlayer(ServerPlayer player, ServerLevel origin, Vec3 position, float yaw, float pitch) {
        var previous = TRAVEL.get();
        TRAVEL.set(new Travel(player, origin));
        try {
            player.teleportTo(origin, position.x, position.y, position.z, yaw, pitch);
        } finally {
            if (previous == null) TRAVEL.remove();
            else TRAVEL.set(previous);
        }
    }

    static void restoreRiding(Entity rider, Entity mount, boolean mounting) {
        var previous = RIDING.get();
        RIDING.set(new Riding(rider, mount, mounting));
        try {
            if (mounting) rider.startRiding(mount, true);
            else rider.stopRiding();
        } finally {
            if (previous == null) RIDING.remove();
            else RIDING.set(previous);
        }
    }

    public static boolean returningTo(ServerPlayer player, ResourceKey<Level> dimension) {
        var travel = TRAVEL.get();
        return travel != null && travel.player() == player && travel.origin().dimension().equals(dimension);
    }

    public static boolean rejoining(ServerPlayer player, ServerLevel level) {
        var travel = TRAVEL.get();
        return travel != null && travel.player() == player && travel.origin() == level;
    }

    public static boolean restoringRide(Entity rider, Entity mount, boolean mounting) {
        var riding = RIDING.get();
        return riding != null && riding.rider() == rider && riding.mount() == mount && riding.mounting() == mounting;
    }
}
