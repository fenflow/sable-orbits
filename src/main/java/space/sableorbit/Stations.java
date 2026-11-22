package space.sableorbit;

import com.hbm.packet.PlayerInformPayload;
import com.ntmspace.orbit.*;
import com.ntmspace.tileentity.station.TileEntityOrbitalStationComputer;
import dev.golem.core.net.GolemNetwork;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.*;

final class Stations {
    static final dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType<String> TICKET =
        dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.create(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sableorbits","station"),com.mojang.serialization.Codec.STRING);
    static void initialize() { /* Register the ticket codec before the first level loads. */ }
    static void track(ServerSubLevel part) {
        if (OrbitSpaces.isolated(part.getLevel())) SubLevelContainer.getContainer(part.getLevel()).addForceLoadTicket(part,TICKET,"station");
    }
    static CompoundTag info(ServerSubLevel part) {
        var data = part.getUserDataTag();
        return data == null ? new CompoundTag() : data.getCompound("sableorbits");
    }
    static void set(ServerSubLevel part, CompoundTag info) {
        var data = part.getUserDataTag();
        if (data == null) data = new CompoundTag();
        data.put("sableorbits", info);
        part.setUserDataTag(data);
        StationLife.remember(part,info);
        track(part);
    }
    static OrbitalStation nativeStation(ServerSubLevel part) {
        var data = info(part);
        return data.hasUUID("root") ? StationData.get(part.getLevel().getServer())
            .getStationFromPosition(data.getInt("x") * 1024, data.getInt("z") * 1024) : null;
    }
    static List<ServerSubLevel> loaded(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        return container == null ? List.of() : List.copyOf(container.getAllSubLevels());
    }
    static ServerSubLevel root(ServerSubLevel part) {
        var tag = info(part);
        return tag.hasUUID("root") ? (ServerSubLevel)SubLevelContainer.getContainer(part.getLevel()).getSubLevel(tag.getUUID("root")) : null;
    }
    static void tick(ServerLevel level) {
        Flight.settleArrivals(level);
        if (level.dimension().equals(Orbit.ORBIT)) Hooks.migrateLoaded(level);
        if (OrbitSpaces.isolated(level)) {
            for (var part : loaded(level)) if (root(part) == part) {
                var station = nativeStation(part);
                if (station != null && station.state != OrbitalStation.StationState.ORBIT) {
                    var phase = station.state;
                    boolean starting = station.stateTimer == 0;
                    station.update(level);
                    // A travelling station occupies its own dimension.
                    if (starting || phase != station.state)
                        for (var player : level.players()) sendSky(player,station);
                }
            }
        }
        if (OrbitSpaces.isolated(level)) {
            if (level.getGameTime() % 20 != 0) return;
            for (var part : loaded(level)) {
                if (!part.isRemoved() && root(part) == part) tickStation(part);
            }
            for (var player : List.copyOf(level.players())) playerFall(player);
        } else if (Orbit.levelOf(Orbit.bodyOf(level)).equals(level.dimension())) {
            var visited = new HashSet<ServerSubLevel>();
            for (var part : loaded(level)) {
                if (part.isRemoved() || part.logicalPose().position().y < 2000 || visited.contains(part)) continue;
                var parts = Flight.group(part);
                visited.addAll(parts);
                var crew = Flight.crew(parts);
                if (crew.isEmpty()) {
                    for (var member : parts) {
                        var handle = RigidBodyHandle.of(member);
                        if (handle != null && handle.isValid())
                            handle.addLinearAndAngularVelocity(new Vector3d(0, -Math.max(0, handle.getLinearVelocity().y() + 10), 0), new Vector3d());
                    }
                } else {
                    var returningRoot=parts.stream().filter(s -> {
                        var user=s.getUserDataTag();
                        if (user==null) return false;
                        var landed=user.getCompound("sableorbits_landed");
                        return landed.hasUUID("root") && landed.getUUID("root").equals(s.getUniqueId());
                    }).findFirst().orElse(part);
                    launch(returningRoot,parts,crew);
                }
            }
        }
    }
    static void launch(ServerSubLevel root, List<ServerSubLevel> parts, List<ServerPlayer> crew) {
        var origin = root.getLevel();
        var data = StationData.get(origin.getServer());
        var user = root.getUserDataTag();
        var old = user == null ? new CompoundTag() : user.getCompound("sableorbits_landed");
        boolean returning = old.hasUUID("root") && old.getUUID("root").equals(root.getUniqueId());
        var placeholder = data.getStationFromPosition(old.getInt("x")*1024,old.getInt("z")*1024);
        if (returning && placeholder!=null && !placeholder.hasStation && StationLife.reserved(origin.getServer(),old.getInt("x"),old.getInt("z"))) {
            StationLife.deregister(data,placeholder);
            placeholder=null;
        }
        var grid = returning && placeholder == null
            ? new int[]{old.getInt("x"),old.getInt("z")} : data.findFreeSpace();
        if (data.getStationFromPosition(grid[0]*1024,grid[1]*1024) != null) return;
        var target = OrbitSpaces.open(origin.getServer(), "station_" + root.getUniqueId());
        var p = root.logicalPose().position();
        var offset = new Vec3(512 - p.x, 128 - p.y, 512 - p.z);
        var moved = Flight.transfer(parts, target, offset);
        if (moved.isEmpty()) return;
        var station = data.addStation(grid[0], grid[1], Orbit.bodyOf(origin));
        station.hasStation = true;
        station.name = root.getName() == null || root.getName().isBlank() ? "Station " + grid[0] + ", " + grid[1] : root.getName();
        var tag = new CompoundTag();
        tag.putUUID("root", root.getUniqueId());
        tag.putUUID("owner", old.hasUUID("owner") ? old.getUUID("owner") : crew.getFirst().getUUID());
        tag.putBoolean("public",old.getBoolean("public"));
        tag.putInt("x", grid[0]);
        tag.putInt("z", grid[1]);
        tag.putDouble("launchX", p.x);
        tag.putDouble("launchZ", p.z);
        for (var part : moved) set(part, tag.copy());
        data.setDirty();
    }
    static void tickStation(ServerSubLevel root) {
        var tag = info(root);
        var parts = Flight.group(root);
        var station = nativeStation(root);
        if (station == null) return;
        for (var part : parts) if (!info(part).hasUUID("root")) set(part,tag.copy());
        refreshEquipment(station,parts);
        var crew = Flight.crew(parts);
        if (!tag.hasUUID("owner") && !crew.isEmpty()) tag.putUUID("owner",crew.getFirst().getUUID());
        if (Rendezvous.linked(tag) || parts.stream().flatMap(part -> part.getPlot().getLoadedChunks().stream())
                .flatMap(chunk -> chunk.getChunk().getBlockEntities().values().stream()).anyMatch(TileEntityOrbitalStationComputer.class::isInstance)) {
            tag.remove("decay");
        } else if (!crew.isEmpty()) {
            int seconds = Math.max(0, 300 - tag.getInt("decay") - 1);
            tag.putInt("decay", 300 - seconds);
            String time = "%d:%02d".formatted(seconds / 60, seconds % 60);
            for (var player : crew) GolemNetwork.sendToPlayer(player, PlayerInformPayload.CODEC,
                PlayerInformPayload.of(7342, 1200, ChatFormatting.YELLOW, "The station will fall in " + time + " without a station computer..."));
            if (seconds == 0) land(root, tag.getDouble("launchX"), tag.getDouble("launchZ"));
        }
        if (!root.isRemoved()) Rendezvous.checkDistance(root);
    }
    static void refreshEquipment(OrbitalStation station,List<ServerSubLevel> parts) {
        var equipment = (space.sableorbit.mixin.EquipmentAccess)(Object)station;
        equipment.sableorbits$ports().clear();
        equipment.sableorbits$mainPort(null);
        equipment.sableorbits$engines().clear();
        for (var part : parts) for (var holder : part.getPlot().getLoadedChunks())
            for (var tile : holder.getChunk().getBlockEntities().values()) {
                if (tile.isRemoved()) continue;
                if (tile instanceof com.ntmspace.tileentity.station.TileEntityOrbitalStation port) {
                    station.addPort(port);
                    Docking.keepApproachLoaded(port);
                }
                if (tile instanceof Propulsion engine) equipment.sableorbits$engines().add(engine);
            }
        station.hasEngines = !station.engines().isEmpty();
    }
    static boolean land(ServerSubLevel root, double x, double z) {
        var station = nativeStation(root);
        if (station == null || Rendezvous.linked(info(root)) || station.state != OrbitalStation.StationState.ORBIT || !Orbit.isLandable(station.orbiting)) return false;
        var target = root.getLevel().getServer().getLevel(Orbit.levelOf(station.orbiting));
        if (target == null) return false;
        var p = root.logicalPose().position();
        var parts = Flight.transfer(Flight.group(root), target, new Vec3(x - p.x, 800 - p.y, z - p.z));
        if (parts.isEmpty()) return false;
        for (var part : parts) {
            var previous = info(part);
            part.getUserDataTag().put("sableorbits_landed",previous.copy());
            part.getUserDataTag().remove("sableorbits");
        }
        StationLife.reserve(target.getServer(),root.getUniqueId(),station);
        StationLife.deregister(StationData.get(target.getServer()),station);
        return true;
    }
    private static final Map<ServerPlayer,OrbitalStation> lastSky = new WeakHashMap<>();
    private static void sendSky(ServerPlayer player, OrbitalStation station) {
        var codec = com.ntmspace.entity.rocket.RocketStationSync.CODEC;
        if (GolemNetwork.canSendToPlayer(player,codec)) {
            GolemNetwork.sendToPlayer(player,codec,new com.ntmspace.entity.rocket.RocketStationSync.Payload(station));
            lastSky.put(player,station);
        }
    }
    static void forgetSky(ServerPlayer player) { lastSky.remove(player); }

    static void playerFall(ServerPlayer player) {
        if (player.getVehicle() instanceof StationDock) return;
        var level = player.serverLevel();
        ServerSubLevel nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (var part : loaded(level)) {
            if (!info(part).hasUUID("root")) continue;
            var b = part.boundingBox();
            double dx = gap(player.getX(), b.minX(), b.maxX()), dy = gap(player.getY(), b.minY(), b.maxY()), dz = gap(player.getZ(), b.minZ(), b.maxZ());
            double distance = dx*dx + dy*dy + dz*dz;
            if (distance < best) { best = distance; nearest = part; }
        }
        if (nearest != null) {
            var station = nativeStation(nearest);
            if (station != null) {
                player.getPersistentData().putString("sableorbits_body",station.orbiting.name);
                if (station.state == OrbitalStation.StationState.ORBIT || lastSky.get(player) != station) sendSky(player,station);
            }
        }
        if (best > 256 * 256 && player.getPersistentData().contains("sableorbits_body")) {
            var body = com.ntmspace.world.SolarSystem.body(player.getPersistentData().getString("sableorbits_body"));
            if (body != null && Orbit.isLandable(body)) SpaceTeleporter.teleport(player, Orbit.levelOf(body), player.getX(), 800, player.getZ(), false);
        }
    }
    static double gap(double p, double min, double max) { return Math.max(0, Math.max(min - p, p - max)); }
}
