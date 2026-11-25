package space.sableorbit;

import com.ntmspace.orbit.*;
import com.ntmspace.entity.rocket.EntityRideableRocket;
import com.ntmspace.tileentity.station.TileEntityOrbitalStation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import space.sableorbit.mixin.EquipmentAccess;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class Docking {
    private static final TicketType<BlockPos> BERTH =
        TicketType.create("sableorbits_berth", BlockPos::compareTo, 60);
    private static final TicketType<Integer> FLIGHT =
        TicketType.create("sableorbits_dock_flight", Integer::compare, 3);
    private static final Set<TileEntityOrbitalStation> seenPorts = Collections.newSetFromMap(new WeakHashMap<>());

    static void keepBerthLoaded(TileEntityOrbitalStation dock) {
        if (dock.getLevel() instanceof ServerLevel level && OrbitSpaces.isolated(level)) {
            // A newly loaded port needs one window to reconnect to a saved docked entity.
            boolean loading = seenPorts.add(dock);
            var attached = dock.getDocked();
            if (attached != null && attached.entity().level() == level && !attached.entity().isRemoved()) {
                level.getChunkSource().addRegionTicket(BERTH, attached.entity().chunkPosition(), 2, dock.getBlockPos());
            } else if (loading) {
                // A rotated capsule may have been saved in a chunk adjacent to the port.
                level.getChunkSource().addRegionTicket(BERTH, new ChunkPos(position(dock)), 3, dock.getBlockPos());
                level.resetEmptyTime();
            }
        }
    }

    public static void keepFlightLoaded(EntityRideableRocket rocket) {
        if (rocket.level() instanceof ServerLevel level && OrbitSpaces.isolated(level))
            keepFlightLoaded(level, rocket.chunkPosition(), rocket.getId());
    }

    private static void keepFlightLoaded(ServerLevel level, ChunkPos chunk, int rocketId) {
        // A forced timed ticket leaves a separate force-tick entry behind on expiry.
        level.getChunkSource().addRegionTicket(FLIGHT, chunk, 2, rocketId);
        level.resetEmptyTime();
    }

    public static ServerSubLevel addressed(MinecraftServer server,int x,int z) {
        var station = StationData.get(server).getStationFromPosition(x,z);
        if (station == null) return null;
        return Rendezvous.stations(server).stream().filter(s -> Stations.nativeStation(s) == station).findFirst().orElse(null);
    }
    public static OrbitalStation nearby(ServerLevel level,int x,int z) {
        ServerSubLevel nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (var part : Stations.loaded(level)) {
            if (Stations.root(part) == null) continue;
            var b = part.boundingBox();
            double dx = Stations.gap(x,b.minX(),b.maxX()), dz = Stations.gap(z,b.minZ(),b.maxZ());
            double d = dx*dx+dz*dz;
            if (d < best) { best = d; nearest = part; }
        }
        return nearest == null ? null : Stations.nativeStation(nearest);
    }
    public static BlockPos position(TileEntityOrbitalStation dock) {
        var level = dock.getLevel();
        return level != null && OrbitSpaces.isolated(level)
            ? BlockPos.containing(Sable.HELPER.projectOutOfSubLevel(level,dock.getBlockPos().getCenter())) : dock.getBlockPos();
    }
    public static void attached(TileEntityOrbitalStation dock,StationDock craft) {
        if (!(dock.getLevel() instanceof ServerLevel level) || !OrbitSpaces.isolated(level)) return;
        if (!(Sable.HELPER.getContaining(level,dock.getBlockPos()) instanceof ServerSubLevel part)) return;
        var tag = craft.entity().getPersistentData();
        tag.putUUID("sableorbits_berth",part.getUniqueId());
        tag.putLong("sableorbits_port", dock.getBlockPos().asLong());
        keepBerthLoaded(dock);
    }
    public static void follow(EntityRideableRocket craft) {
        if (!(craft.level() instanceof ServerLevel level)) return;
        var tag = craft.getPersistentData();
        if (!tag.hasUUID("sableorbits_berth")) return;
        if (craft.isUndocking()) { tag.remove("sableorbits_berth"); return; }
        var pos = BlockPos.of(tag.getLong("sableorbits_port"));
        if (!(Sable.HELPER.getContaining(level,pos) instanceof ServerSubLevel part) || !part.getUniqueId().equals(tag.getUUID("sableorbits_berth"))
                || !(level.getBlockEntity(pos) instanceof TileEntityOrbitalStation dock)) { tag.remove("sableorbits_berth"); return; }
        if (dock.getDocked() == null) dock.dockRocket(craft);
        if (dock.getDocked() != craft) { tag.remove("sableorbits_berth"); return; }
        var anchor = part.logicalPose().transformPosition(pos.getCenter().add(0,1-craft.getBbHeight(),0));
        craft.setPos(anchor.x,anchor.y,anchor.z);
    }
    public static TileEntityOrbitalStation port(EntityRideableRocket rocket) {
        var tag = rocket.getPersistentData();
        var level = (ServerLevel)rocket.level();
        if (tag.hasUUID("sableorbits_dock")) {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(tag.getUUID("sableorbits_dock")) instanceof ServerSubLevel part) {
                var dock = receivingPort(Stations.nativeStation(part));
                if (dock != null && dock.getLevel() == level && level.getBlockEntity(dock.getBlockPos()) == dock) return dock;
            }
        }
        return null;
    }

    public static TileEntityOrbitalStation receivingPort(OrbitalStation station) {
        if (station == null) return null;
        var nativePort = station.getPort();
        if (nativePort == null) return null;
        // Ordinary NTM ports can stow the previous craft; leave that selection to NTM.
        if (nativePort.getLevel() != null && !OrbitSpaces.isolated(nativePort.getLevel()))
            return nativePort.isRemoved() ? null : nativePort;
        // A moving berth must be unreserved and empty even before NTM next updates hasDocked.
        for (var port : ((EquipmentAccess)(Object)station).sableorbits$ports().values()) {
            if (port.isRemoved() || port.isReserved || port.hasDocked || port.getDocked() != null) continue;
            var level = port.getLevel();
            if (level != null && level.hasChunkAt(port.getBlockPos()) && level.getBlockEntity(port.getBlockPos()) == port) return port;
        }
        return null;
    }
    public static void rememberDestination(EntityRideableRocket rocket, TileEntityOrbitalStation dock) {
        var level = dock.getLevel();
        if (level != null && OrbitSpaces.isolated(level)
                && Sable.HELPER.getContaining(level, dock.getBlockPos()) instanceof ServerSubLevel plot) {
            var root = Stations.root(plot);
            if (root != null) rocket.getPersistentData().putUUID("sableorbits_dock", root.getUniqueId());
        }
    }
    public static boolean redirect(Entity passenger,double x,double z) {
        var server = passenger.getServer();
        if (server == null) return false;
        var station = addressed(server,(int)Math.floor(x),(int)Math.floor(z));
        if (station == null) return false;
        var dock = receivingPort(Stations.nativeStation(station));
        if (dock == null) {
            if (passenger.getRootVehicle() instanceof EntityRideableRocket rocket) Berth.abortApproach(rocket);
            return true;
        }
        var pos = Sable.HELPER.projectOutOfSubLevel(station.getLevel(),dock.getBlockPos().getCenter().add(0,-12,0));
        var vehicle = passenger.getRootVehicle();
        vehicle.getPersistentData().remove("sableorbits_gap");
        vehicle.getPersistentData().putUUID("sableorbits_dock",station.getUniqueId());
        keepFlightLoaded(station.getLevel(), new ChunkPos(BlockPos.containing(pos)), vehicle.getId());
        SpaceTeleporter.teleport(passenger,station.getLevel().dimension(),pos.x,pos.y,pos.z,false);
        return true;
    }
}





