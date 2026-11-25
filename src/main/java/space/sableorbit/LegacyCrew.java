package space.sableorbit;

import com.ntmspace.orbit.Orbit;
import com.ntmspace.orbit.OrbitalStation;
import com.ntmspace.orbit.StationData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.List;

final class LegacyCrew {
    private static final String KEY = "sableorbits_legacy_crew";

    static void register() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) recover(player);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) departing(player);
        });
    }

    static void remember(ServerPlayer player, ServerSubLevel hull, OrbitalStation disk) {
        if (player.serverLevel() != hull.getLevel() || !player.isAlive()) return;
        var tag = new CompoundTag();
        tag.putUUID("root", hull.getUniqueId());
        tag.putInt("x", disk.dX);
        tag.putInt("z", disk.dZ);
        var origin = player.position();
        write(tag, "origin", origin);
        var worldFeet = Sable.HELPER.projectOutOfSubLevel(hull.getLevel(), origin);
        write(tag, "feet", hull.logicalPose().transformPositionInverse(worldFeet));
        player.getPersistentData().put(KEY, tag);
    }

    static void forget(ServerPlayer player) { player.getPersistentData().remove(KEY); }

    private static void departing(ServerPlayer player) {
        var level = player.serverLevel();
        if (!level.dimension().equals(Orbit.ORBIT)) return;
        // A player may board a previously vetoed hull and log out before its next transfer attempt.
        for (var part : Stations.loaded(level)) {
            if (part.isRemoved() || Stations.root(part) != part || !Flight.crew(List.of(part)).contains(player)) continue;
            var station = Stations.nativeStation(part);
            if (station != null && station.hasStation && Hooks.pendingMigration(level, station.dX * 1024, station.dZ * 1024)) {
                remember(player, part, station);
                return;
            }
        }
        if (player.getPersistentData().get(KEY) instanceof CompoundTag tag && !atOrigin(player, tag)) forget(player);
    }

    static void retry(ServerLevel level) {
        // A successful boarding changes the level's player list.
        for (var player : List.copyOf(level.players())) recover(player);
    }

    private static void recover(ServerPlayer player) {
        if (!(player.getPersistentData().get(KEY) instanceof CompoundTag tag)) return;
        if (!tag.hasUUID("root") || !atOrigin(player, tag) || !player.isAlive()) {
            forget(player);
            return;
        }
        var server = player.serverLevel().getServer();
        var id = tag.getUUID("root");
        int x = tag.getInt("x"), z = tag.getInt("z");
        var disk = StationData.get(server).getStationFromPosition(x * 1024, z * 1024);
        var address = OrbitSpaces.data(server).addresses.getCompound(x + "," + z);
        if (disk == null || !disk.hasStation || address.getBoolean("landed")
                || (address.hasUUID("id") && !id.equals(address.getUUID("id")))) {
            forget(player);
            return;
        }
        if (!address.hasUUID("id") || address.getString("dimension").isEmpty()) return;
        var destination = server.getLevel(OrbitSpaces.key(address.getString("dimension")));
        var plots = destination == null ? null : SubLevelContainer.getContainer(destination);
        if (plots == null || !(plots.getSubLevel(id) instanceof ServerSubLevel hull) || hull.isRemoved()
                || Stations.root(hull) != hull || Stations.nativeStation(hull) != disk) return;
        var feet = hull.logicalPose().transformPosition(read(tag, "feet"));
        if (Boarding.arrive(player, hull, feet, true)) forget(player);
    }

    private static boolean atOrigin(ServerPlayer player, CompoundTag tag) {
        return player.level().dimension().equals(Orbit.ORBIT) && player.position().distanceToSqr(read(tag, "origin")) <= 0.0625 * 0.0625;
    }

    private static void write(CompoundTag tag, String name, Vec3 point) {
        tag.putDouble(name + "X", point.x);
        tag.putDouble(name + "Y", point.y);
        tag.putDouble(name + "Z", point.z);
    }

    private static Vec3 read(CompoundTag tag, String name) {
        return new Vec3(tag.getDouble(name + "X"), tag.getDouble(name + "Y"), tag.getDouble(name + "Z"));
    }
}
