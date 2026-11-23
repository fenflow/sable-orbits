package space.sableorbit;

import com.ntmspace.orbit.OrbitalStation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.*;
import java.util.*;

final class Rendezvous {
    static List<ServerSubLevel> stations(MinecraftServer server) {
        var result = new ArrayList<ServerSubLevel>();
        for (var level : server.getAllLevels()) if (OrbitSpaces.isolated(level))
            for (var part : Stations.loaded(level)) if (Stations.root(part) == part && StationLife.intact(part)) result.add(part);
        return result;
    }
    static boolean linked(CompoundTag tag) { return tag.hasUUID("host") || !tag.getCompound("guests").isEmpty(); }
    static boolean owner(ServerPlayer player, ServerSubLevel root) {
        var tag = Stations.info(root);
        return tag.hasUUID("owner") && tag.getUUID("owner").equals(player.getUUID());
    }
    private static boolean operates(ServerPlayer player, ServerSubLevel station) {
        return owner(player,station) || (player.containerMenu instanceof Console console && console.controls(Set.of(station)));
    }
    static boolean available(ServerSubLevel guest, ServerSubLevel host) {
        var a = Stations.nativeStation(guest);
        var b = Stations.nativeStation(host);
        return guest != host && !linked(Stations.info(guest)) && !Stations.info(host).hasUUID("host")
            && a != null && b != null && a.orbiting == b.orbiting && a.state == OrbitalStation.StationState.ORBIT && b.state == a.state;
    }
    static CompoundTag entries(CompoundTag tag, String key) {
        if (!tag.contains(key)) tag.put(key, new CompoundTag());
        return tag.getCompound(key);
    }
    static boolean request(ServerPlayer player, ServerSubLevel guest, ServerSubLevel host) {
        if (!operates(player, guest) || !available(guest, host)) return false;
        if (Stations.info(host).getBoolean("public")) return join(guest, host, true);
        entries(Stations.info(host), "requests").putBoolean(guest.getUniqueId().toString(), true);
        return true;
    }
    static boolean decide(ServerPlayer player, ServerSubLevel host, ServerSubLevel guest, boolean accept) {
        var requests = entries(Stations.info(host), "requests");
        String id = guest.getUniqueId().toString();
        if (!owner(player, host) || !requests.contains(id)) return false;
        if (accept && (!available(guest, host) || !join(guest, host, false))) return false;
        requests.remove(id);
        return true;
    }
    static AABB bounds(ServerSubLevel root) {
        return bounds(Flight.group(root));
    }
    private static AABB bounds(List<ServerSubLevel> parts) {
        AABB box = null;
        for (var part : parts) {
            var b = part.boundingBox();
            var next = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
            box = box == null ? next : box.minmax(next);
        }
        return box;
    }
    private static boolean join(ServerSubLevel guest, ServerSubLevel host, boolean publicVisit) {
        var hostParts = Flight.group(host);
        var guestParts = Flight.group(guest);
        var a = bounds(hostParts);
        var b = bounds(guestParts);
        if (a == null || b == null) return false;
        var obstacles = Stations.loaded(host.getLevel()).stream().filter(s -> Stations.root(s) == s && s != host)
            .map(Rendezvous::bounds).filter(Objects::nonNull).map(box -> box.inflate(1)).toList();
        var hostHull = HullDistance.hull(hostParts);
        var guestHull = HullDistance.hull(guestParts);
        double width = (a.getXsize() + b.getXsize()) / 2, depth = (a.getZsize() + b.getZsize()) / 2;
        var hostCenter = a.getCenter();
        var guestCenter = b.getCenter();
        for (int i = 0; i < 72; i++) {
            double theta = 2 * Math.PI * i / 72;
            double x = Math.cos(theta), z = Math.sin(theta);
            double low = 0, high = width + depth + 300;
            for (int j = 0; j < 40; j++) {
                double r = (low + high) / 2;
                if (Math.hypot(Math.max(0, Math.abs(x*r)-width), Math.max(0, Math.abs(z*r)-depth)) < 150) low = r;
                else high = r;
            }
            var offset = hostCenter.add(x*high, 0, z*high).subtract(guestCenter);
            var gap = HullDistance.closest(hostHull, guestHull, offset, Double.POSITIVE_INFINITY);
            if (gap == null) return false;
            var physicalOffset = offset.subtract(gap.normalize().scale(gap.length()-150));
            var movedBounds = b.move(physicalOffset);
            if (obstacles.stream().anyMatch(box -> box.intersects(movedBounds))) continue;
            var copies = Flight.transfer(guestParts, host.getLevel(), physicalOffset);
            if (copies.isEmpty()) return false;
            var hostVelocity = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(host).getLinearVelocity(new org.joml.Vector3d());
            for (var copy : copies) {
                var body = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(copy);
                body.addLinearAndAngularVelocity(new org.joml.Vector3d(hostVelocity).sub(body.getLinearVelocity()),new org.joml.Vector3d());
            }
            var tag = Stations.info(copies.getFirst());
            for (var station : stations(host.getLevel().getServer())) Stations.info(station).getCompound("requests").remove(guest.getUniqueId().toString());
            tag.remove("requests");
            tag.putUUID("host", host.getUniqueId());
            tag.putBoolean("publicVisit", publicVisit);
            entries(Stations.info(host), "guests").putBoolean(guest.getUniqueId().toString(), true);
            return true;
        }
        return false;
    }
    static boolean leave(ServerPlayer player, ServerSubLevel actor, ServerSubLevel other) {
        boolean requester = Stations.info(actor).hasUUID("host");
        if (!(requester ? operates(player,actor) : owner(player,actor))) return false;
        var guest = requester ? actor : other;
        var host = requester ? other : actor;
        var tag = Stations.info(guest);
        if (!tag.hasUUID("host") || !host.getUniqueId().equals(tag.getUUID("host"))) return false;
        tag.putBoolean(requester ? "guestLeave" : "hostLeave", true);
        if (tag.getBoolean("guestLeave") && (tag.getBoolean("publicVisit") || tag.getBoolean("hostLeave")))
            return separate(guest, host);
        return true;
    }
    private static final Map<ServerSubLevel,net.minecraft.core.BlockPos> sampleBlocks=new WeakHashMap<>();
    private static net.minecraft.core.BlockPos sample(ServerSubLevel ship) {
        var cached=sampleBlocks.get(ship);
        if(cached!=null && !ship.getLevel().isEmptyBlock(cached)) return cached;
        for(var holder:ship.getPlot().getLoadedChunks()) {
            var chunk=holder.getChunk();
            for(int i=0;i<chunk.getSectionsCount();i++) {
                var section=chunk.getSection(i);
                if(section.hasOnlyAir()) continue;
                for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                    if(section.getBlockState(x,y,z).isAir()) continue;
                    var pos=new net.minecraft.core.BlockPos(chunk.getPos().getMinBlockX()+x,chunk.getSectionYFromSectionIndex(i)*16+y,chunk.getPos().getMinBlockZ()+z);
                    sampleBlocks.put(ship,pos);return pos;
                }
            }
        }
        return null;
    }
    static boolean withinRange(ServerSubLevel a,ServerSubLevel b) {
        var pa=sample(a);var pb=sample(b);
        return pa!=null && pb!=null && a.logicalPose().transformPosition(pa.getCenter()).distanceToSqr(b.logicalPose().transformPosition(pb.getCenter()))<=250*250;
    }
    static void checkDistance(ServerSubLevel guest) {
        var tag = Stations.info(guest);
        if (!tag.hasUUID("host")) return;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(guest.getLevel());
        if (!(container.getSubLevel(tag.getUUID("host")) instanceof ServerSubLevel host)) return;
        if(withinRange(host,guest)) return;
        var hostParts = Flight.group(host);
        var guestParts = Flight.group(guest);
        var a = bounds(hostParts);
        var b = bounds(guestParts);
        if (a == null || b == null) return;
        double x = Math.max(0, Math.max(a.minX-b.maxX, b.minX-a.maxX));
        double y = Math.max(0, Math.max(a.minY-b.maxY, b.minY-a.maxY));
        double z = Math.max(0, Math.max(a.minZ-b.maxZ, b.minZ-a.maxZ));
        if (x*x + y*y + z*z > 250*250 || HullDistance.closest(HullDistance.hull(hostParts), HullDistance.hull(guestParts), Vec3.ZERO, 250) == null)
            separate(guest, host);
    }
    private static boolean separate(ServerSubLevel guest, ServerSubLevel host) {
        var p = guest.logicalPose().position();
        var copies = OrbitSpaces.transfer(Flight.group(guest), OrbitSpaces.homeName(host.getLevel(), guest.getUniqueId()),
            new Vec3(512-p.x, 128-p.y, 512-p.z));
        if (copies.isEmpty()) return false;
        var tag = Stations.info(copies.getFirst());
        for (String key : List.of("host","hostLeave","guestLeave","publicVisit")) tag.remove(key);
        entries(Stations.info(host), "guests").remove(guest.getUniqueId().toString());
        return true;
    }
}

