package space.sableorbit;

import com.ntmspace.orbit.*;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class StationLife {
    private static String key(int x,int z) { return x+","+z; }
    static void deregister(StationData data, OrbitalStation station) {
        station.hasStation=false;
        data.removeStation(station);
    }
    static void reserve(MinecraftServer server, UUID id, OrbitalStation station) {
        var store=OrbitSpaces.data(server);
        var entry=new CompoundTag(); entry.putUUID("id",id); entry.putBoolean("landed",true);
        store.addresses.put(key(station.dX,station.dZ),entry); store.setDirty();
    }
    static void remember(ServerSubLevel root, CompoundTag tag) {
        if(!tag.hasUUID("root") || !tag.getUUID("root").equals(root.getUniqueId()) || !OrbitSpaces.isolated(root.getLevel())) return;
        var store=OrbitSpaces.data(root.getLevel().getServer());
        String address=key(tag.getInt("x"),tag.getInt("z"));
        var old=store.addresses.getCompound(address);
        var dimension=root.getLevel().dimension().location().getPath();
        if(old.hasUUID("id") && old.getUUID("id").equals(root.getUniqueId()) && dimension.equals(old.getString("dimension")) && !old.getBoolean("landed")) return;
        var value=new CompoundTag();value.putUUID("id",root.getUniqueId());value.putString("dimension",dimension);
        store.addresses.put(address,value);store.setDirty();
    }
    public static boolean reserved(MinecraftServer server,int x,int z) {
        return OrbitSpaces.data(server).addresses.getCompound(key(x,z)).getBoolean("landed");
    }
    private static boolean computer(List<ServerSubLevel> parts, FlightTopology topology) {
        return parts.stream().anyMatch(part -> topology.equipment(part).computer());
    }
    static boolean intact(ServerSubLevel part) {
        return part.getPlot().getLoadedChunks().stream().anyMatch(c-> java.util.Arrays.stream(c.getChunk().getSections()).anyMatch(section -> !section.hasOnlyAir()));
    }
    static void tick(MinecraftServer server) {
        if (server.getTickCount()<40 || server.getTickCount()%20!=0) return;
        reconcile(server);
    }
    static void reconcile(MinecraftServer server) {
        var store=OrbitSpaces.data(server);
        var nativeData=StationData.get(server);
        var candidates=List.copyOf(nativeData.getStations());
        var present=new HashSet<String>();
        boolean ready=true;
        for(var level:java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(),false).toList()) {
            if (!OrbitSpaces.isolated(level)) continue;
            var container=SubLevelContainer.getContainer(level);
            if(container==null) { ready=false; continue; }
            for(var id:container.getAllTickets().keySet()) if(container.getSubLevel(id)==null) ready=false;
            var all=Stations.loaded(level);
            var checks = new Rendezvous.DistanceChecks(level);
            var intactPlots = new IdentityHashMap<ServerSubLevel, Boolean>();
            for(var part:all) {
                if(part.isRemoved() || !intactPlots.computeIfAbsent(part, StationLife::intact)) continue;
                var t=Stations.info(part);
                if(!t.hasUUID("root")) {
                    var parent=part.getSplitFromSubLevel();
                    var base=parent==null ? null : container.getSubLevel(parent);
                    if(base instanceof ServerSubLevel s && Stations.info(s).hasUUID("root")) Stations.set(part,Stations.info(s).copy());
                    else {
                        var nearest=all.stream().filter(s->Stations.root(s)==s).min(Comparator.comparingDouble(s->s.logicalPose().position().distanceSquared(part.logicalPose().position())));
                        if(nearest.isPresent()) Stations.set(part,Stations.info(nearest.get()).copy());
                    }
                    t=Stations.info(part);
                    if (t.hasUUID("root")) checks.invalidateGroups();
                }
                if(!t.hasUUID("root")) continue;
                var root=Stations.root(part);
                if(root==null && container.getAllTickets().containsKey(t.getUUID("root"))) continue;
                var declaredRoot=root;
                if(declaredRoot==null) {
                    var id=t.getUUID("root");
                    declaredRoot=Rendezvous.stations(server).stream().filter(s -> s.getUniqueId().equals(id)).findFirst().orElse(null);
                }
                if(declaredRoot!=null && (Stations.info(declaredRoot).getInt("x")!=t.getInt("x") || Stations.info(declaredRoot).getInt("z")!=t.getInt("z"))) {
                    var oldRoot=t.getUUID("root"); var ax=t.getInt("x");var az=t.getInt("z");
                    for(var member:all) {
                        var m=Stations.info(member);
                        if(m.hasUUID("root") && m.getUUID("root").equals(oldRoot) && m.getInt("x")==ax && m.getInt("z")==az)
                            m.putUUID("root",part.getUniqueId());
                    }
                    root=part;
                    for(var key:List.of("guests","requests","host","publicVisit","guestLeave","hostLeave","decay")) t.remove(key);
                    t.putBoolean("public",true);
                    checks.invalidateGroups();
                    if(!computer(checks.parts(part), checks.topology())) t.putLong("expires",server.overworld().getGameTime()+6000);
                    t.putBoolean("needsHome",true);
                }
                var record=store.addresses.getCompound(key(t.getInt("x"),t.getInt("z")));
                if ((root==null || root==part) && record.hasUUID("id") && !record.getUUID("id").equals(part.getUniqueId())) {
                    var other=Rendezvous.stations(server).stream().filter(s->s.getUniqueId().equals(record.getUUID("id"))).findFirst();
                    if (other.isPresent()) {
                        if (split(part, other.get(), checks)) checks.invalidateTopology();
                        continue;
                    }
                }
                if(root==null || !intactPlots.computeIfAbsent(root, StationLife::intact)) {
                    var lost=t.getUUID("root");
                    for(var member:all) {
                        var m=Stations.info(member);
                        if(m.hasUUID("root") && m.getUUID("root").equals(lost)) m.putUUID("root",part.getUniqueId());
                        if(m.hasUUID("host") && m.getUUID("host").equals(lost)) m.putUUID("host",part.getUniqueId());
                        var guests=m.getCompound("guests");
                        if(guests.contains(lost.toString())) {
                            guests.remove(lost.toString());
                            guests.putBoolean(part.getUniqueId().toString(),true);
                        }
                    }
                    root=part;
                    checks.invalidateGroups();
                }
                if(root!=part && root.logicalPose().position().distanceSquared(part.logicalPose().position())>250*250
                    && !checks.parts(root).contains(part)
                    && HullDistance.closest(checks.hull(root), checks.hull(part), Vec3.ZERO, 250) == null) {
                    if (split(part, root, checks)) checks.invalidateTopology();
                    continue;
                }
                var station=Stations.nativeStation(part);
                if(station==null) continue;
                var address=key(station.dX,station.dZ);
                present.add(address);
                if(root!=part) continue;
                if(t.getBoolean("needsHome")) {
                    var name = "station_" + root.getUniqueId();
                    if (!level.dimension().equals(OrbitSpaces.key(name))) {
                        var p=root.logicalPose().position();
                        var moved=OrbitSpaces.transfer(checks.parts(root),name,new Vec3(512-p.x,128-p.y,512-p.z));
                        if (!moved.isEmpty()) {
                            checks.invalidateTopology();
                            for (var member : moved) Stations.info(member).remove("needsHome");
                        }
                        continue;
                    }
                    t.remove("needsHome");
                }
                remember(root,t);
                if(t.contains("expires")) {
                    var physical=Flight.group(root, true, checks.topology());
                    var receiver=physical.stream().map(Stations::root).filter(Objects::nonNull).filter(other->
                        !Stations.info(other).contains("expires") && Stations.nativeStation(other)!=null).findFirst();
                    if(receiver.isPresent()) {
                        for(var member:checks.parts(root)) Stations.set(member,Stations.info(receiver.get()).copy());
                        checks.invalidateGroups();
                        deregister(nativeData,station); store.addresses.remove(address);store.setDirty();
                        continue;
                    }
                }
                if(t.contains("expires") && computer(checks.parts(root), checks.topology())) t.remove("expires");
                if(t.contains("expires") && server.overworld().getGameTime()>=t.getLong("expires")) {
                    for(var s:checks.parts(root)) container.removeSubLevel(s, SubLevelRemovalReason.REMOVED);
                    checks.invalidateTopology();
                    present.remove(address);
                }
            }
        }
        if(!ready) return;
        for(var st:candidates) {
            var k=key(st.dX,st.dZ);
            var known=store.addresses.getCompound(k);
            if(!st.hasStation || present.contains(k) || known.getBoolean("landed") || Docking.addressed(server,st.dX*1024,st.dZ*1024)!=null) continue;
            // Older releases assigned this exact default name to flying hulls.
            boolean legacy=("Station "+st.dX+", "+st.dZ).equals(st.name) && st.getPort()==null;
            if(legacy && !known.hasUUID("id")) {
                var orbit=server.getLevel(Orbit.ORBIT);
                if(orbit==null || !orbit.isEmptyBlock(new BlockPos(st.dX*1024+512,127,st.dZ*1024+512))) continue;
            }
            if(!known.hasUUID("id") && !legacy) continue;
            if(known.contains("dimension")) {
                var dim=server.forgeGetWorldMap().get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sableorbits",known.getString("dimension"))));
                if(dim!=null && Stations.loaded(dim).stream().noneMatch(StationLife::intact)) {
                    for(var player:List.copyOf(dim.players())) if(Orbit.isLandable(st.orbiting))
                        SpaceTeleporter.teleport(player,Orbit.levelOf(st.orbiting),player.getX(),800,player.getZ(),false);
                    OrbitSpaces.retire(dim);
                }
            }
            deregister(nativeData,st);
            store.addresses.remove(k);store.setDirty();
        }
        for(var level:server.getAllLevels()) if(OrbitSpaces.isolated(level)) repairVisits(level);
    }
    private static boolean split(ServerSubLevel part, ServerSubLevel original, Rendezvous.DistanceChecks checks) {
        var server=part.getLevel().getServer();
        var parent=Stations.nativeStation(original);
        if(parent==null) return false;
        var group=checks.parts(part);
        boolean equipped=computer(group, checks.topology());
        var data=StationData.get(server);
        var addr=data.findFreeSpace();
        if (data.getStationFromPosition(addr[0]*1024,addr[1]*1024) != null) return false;
        var p=part.logicalPose().position();
        var moved=OrbitSpaces.transfer(group,OrbitSpaces.homeName(part.getLevel(),part.getUniqueId()),
            new Vec3(512-p.x,128-p.y,512-p.z));
        if(moved.isEmpty()) return false;
        var station=data.addStation(addr[0],addr[1],parent.orbiting);
        station.name=(equipped?"Station ":"Space debris ")+addr[0]+", "+addr[1];station.hasStation=true;
        var t=Stations.info(original).copy();
        for(var k:List.of("guests","requests","host","publicVisit","guestLeave","hostLeave","decay","expires")) t.remove(k);
        t.putUUID("root",part.getUniqueId());t.putInt("x",addr[0]);t.putInt("z",addr[1]);t.putBoolean("public",true);
        if(!equipped)t.putLong("expires",server.overworld().getGameTime()+6000);
        for(var s:moved)Stations.set(s,t.copy());
        data.setDirty();
        return true;
    }
    private static void repairVisits(ServerLevel level) {
        var roots=new HashMap<UUID,ServerSubLevel>();
        for(var s:Stations.loaded(level)) if(Stations.root(s)==s && Stations.nativeStation(s)!=null) roots.put(s.getUniqueId(),s);
        var orphanGroups=new HashMap<UUID,List<ServerSubLevel>>();
        for(var s:roots.values()) {
            var t=Stations.info(s);
            var guests=t.getCompound("guests");
            for(var id:List.copyOf(guests.getAllKeys())) if(!roots.containsKey(UUID.fromString(id))) guests.remove(id);
            if(t.hasUUID("host") && !roots.containsKey(t.getUUID("host"))) orphanGroups.computeIfAbsent(t.getUUID("host"),u->new ArrayList<>()).add(s);
        }
        for(var group:orphanGroups.values()) {
            Collections.shuffle(group);var host=group.getFirst();var ht=Stations.info(host);
            for(var s:group) {
                var t=Stations.info(s);
                for(var key:List.of("host","publicVisit","guestLeave","hostLeave")) t.remove(key);
                if(s!=host) {
                    t.putUUID("host",host.getUniqueId());t.putBoolean("publicVisit",ht.getBoolean("public"));
                    Rendezvous.entries(ht,"guests").putBoolean(s.getUniqueId().toString(),true);
                }
            }
        }
    }
}

