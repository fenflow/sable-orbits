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

public final class Docking {
    private static final net.minecraft.server.level.TicketType<BlockPos> APPROACH =
        net.minecraft.server.level.TicketType.create("sableorbits_dock",BlockPos::compareTo,60);

    static void keepApproachLoaded(TileEntityOrbitalStation dock) {
        if (dock.getLevel() instanceof ServerLevel level && OrbitSpaces.isolated(level)) {
            var position = position(dock);
            level.getChunkSource().addRegionTicket(APPROACH,new net.minecraft.world.level.ChunkPos(position),2,dock.getBlockPos(),true);
        }
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
            var part = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level).getSubLevel(tag.getUUID("sableorbits_dock"));
            if (part instanceof ServerSubLevel s && Stations.nativeStation(s) != null) return Stations.nativeStation(s).getPort();
        }
        return null;
    }
    public static boolean redirect(Entity passenger,double x,double z) {
        var server = passenger.getServer();
        if (server == null) return false;
        var station = addressed(server,(int)Math.floor(x),(int)Math.floor(z));
        if (station == null) return false;
        var dock = Stations.nativeStation(station).getPort();
        if (dock == null || dock.isRemoved()) return true;
        var pos = Sable.HELPER.projectOutOfSubLevel(station.getLevel(),dock.getBlockPos().getCenter().add(0,-12,0));
        passenger.getRootVehicle().getPersistentData().remove("sableorbits_gap");
        passenger.getRootVehicle().getPersistentData().putUUID("sableorbits_dock",station.getUniqueId());
        SpaceTeleporter.teleport(passenger,station.getLevel().dimension(),pos.x,pos.y,pos.z,false);
        return true;
    }
}





