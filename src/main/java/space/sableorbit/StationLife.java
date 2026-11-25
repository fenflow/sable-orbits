package space.sableorbit;

import com.ntmspace.orbit.*;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class StationLife {
    private static final List<String> ROOT_STATE = List.of("owner", StationAccess.KEY, "decay", "guests", "host",
        "publicVisit", "guestLeave", "hostLeave");

    public static final class AddressSpaceFull extends IllegalStateException {
        public AddressSpaceFull() { super("All station addresses are occupied"); }
    }

    static int[] freeAddress(StationData data) {
        try {
            var next = data.findFreeSpace();
            return data.getStationFromPosition(next[0]*1024, next[1]*1024) == null ? next : null;
        } catch (AddressSpaceFull full) {
            return null;
        }
    }

    private static String key(int x,int z) { return x+","+z; }
    private static final Map<OrbitalStation,CompletableFuture<Boolean>> legacyTerrain = new WeakHashMap<>();
    private static final Map<ServerLevel,Long> lastTerrainCheck = new WeakHashMap<>();

    private static boolean legacyCoreMissing(ServerLevel level, OrbitalStation station, BlockPos core) {
        var pos = new ChunkPos(core);
        var chunks = level.getChunkSource();
        var chunk = chunks.getChunkNow(pos.x,pos.z);
        if (chunk != null) {
            legacyTerrain.remove(station);
            return chunk.getBlockState(core).isAir();
        }
        var check = legacyTerrain.get(station);
        if (check == null) {
            // Check only an otherwise stale legacy marker. Read at most one saved chunk per pass,
            // without generating terrain or bringing a native station into simulation.
            long tick = level.getGameTime();
            if (lastTerrainCheck.getOrDefault(level,Long.MIN_VALUE) == tick) return false;
            lastTerrainCheck.put(level,tick);
            check = chunks.chunkMap.read(pos).handle((saved,error) -> {
                if (error != null) return false;
                if (saved.isEmpty()) return true;
                var tag = saved.get();
                if (!tag.contains("sections",net.minecraft.nbt.Tag.TAG_LIST)) return false;
                for (var raw : tag.getList("sections",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    var section = (CompoundTag)raw;
                    if (section.getByte("Y") != core.getY() >> 4) continue;
                    if (!section.contains("block_states")) return true;
                    var palette = section.getCompound("block_states").getList("palette",net.minecraft.nbt.Tag.TAG_COMPOUND);
                    // A mixed section can be checked at the exact block when the chunk next loads.
                    return palette.size() == 1 && "minecraft:air".equals(palette.getCompound(0).getString("Name"));
                }
                return true;
            });
            legacyTerrain.put(station,check);
        }
        return check.getNow(false);
    }

    static void deregister(StationData data, OrbitalStation station) {
        station.hasStation=false;
        data.removeStation(station);
    }
    static void reserve(MinecraftServer server, UUID id, OrbitalStation station) {
        var store=OrbitSpaces.data(server);
        var entry=new CompoundTag(); entry.putUUID("id",id); entry.putBoolean("landed",true);
        entry.putString("body",station.orbiting.name);
        store.addresses.put(key(station.dX,station.dZ),entry); store.setDirty();
    }
    static void remember(ServerSubLevel root, CompoundTag tag) {
        if(!tag.hasUUID("root") || !tag.getUUID("root").equals(root.getUniqueId()) || tag.contains("detachedBody")
                || !OrbitSpaces.isolated(root.getLevel())) return;
        var store=OrbitSpaces.data(root.getLevel().getServer());
        String address=key(tag.getInt("x"),tag.getInt("z"));
        var old=store.addresses.getCompound(address);
        var dimension=root.getLevel().dimension().location().getPath();
        if (old.getBoolean("landed") && old.hasUUID("id") && !old.getUUID("id").equals(root.getUniqueId())) return;
        var previous = old.getCompound("rootState");
        if(old.hasUUID("id") && old.getUUID("id").equals(root.getUniqueId()) && dimension.equals(old.getString("dimension"))
                && old.contains("rootState") && ROOT_STATE.stream().allMatch(field -> Objects.equals(tag.get(field), previous.get(field)))) return;
        var value=new CompoundTag();value.putUUID("id",root.getUniqueId());value.putString("dimension",dimension);
        var saved = new CompoundTag();
        copyRootState(tag, saved);
        value.put("rootState", saved);
        store.addresses.put(address,value);store.setDirty();
    }
    private static void copyRootState(CompoundTag from, CompoundTag to) {
        for (var field : ROOT_STATE) {
            var value = from.get(field);
            if (value == null) to.remove(field);
            else to.put(field, value.copy());
        }
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
        var promoted = new HashMap<UUID, UUID>();
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
                var record=store.addresses.getCompound(key(t.getInt("x"),t.getInt("z")));
                if (t.contains("detachedBody") || record.getBoolean("landed")) {
                    // The connected parent has already committed its landing. Its disk belongs to
                    // the landed hull, even while this separate section is waiting to acquire a home.
                    var fragmentRoot = Stations.root(part);
                    if (fragmentRoot != null && fragmentRoot != part && intactPlots.computeIfAbsent(fragmentRoot, StationLife::intact)) continue;
                    var body = t.contains("detachedBody") ? com.ntmspace.world.SolarSystem.body(t.getString("detachedBody"))
                        : landedBody(server, record, t);
                    if (body != null && split(part, t, body, checks)) checks.invalidateTopology();
                    continue;
                }
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
                    t.remove(StationAccess.KEY);
                    checks.invalidateGroups();
                    if(!computer(checks.parts(part), checks.topology())) t.putLong("expires",server.overworld().getGameTime()+6000);
                    t.putBoolean("needsHome",true);
                }
                if ((root==null || root==part) && record.hasUUID("id") && !record.getUUID("id").equals(part.getUniqueId())) {
                    var other=Rendezvous.stations(server).stream().filter(s->s.getUniqueId().equals(record.getUUID("id"))).findFirst();
                    if (other.isPresent()) {
                        if (split(part, other.get(), checks)) checks.invalidateTopology();
                        continue;
                    }
                }
                if(root==null || !intactPlots.computeIfAbsent(root, StationLife::intact)) {
                    var lost=t.getUUID("root");
                    if (root != null) copyRootState(Stations.info(root), t);
                    else if (record.hasUUID("id") && record.getUUID("id").equals(lost) && record.contains("rootState"))
                        copyRootState(record.getCompound("rootState"), t);
                    promoted.put(lost, part.getUniqueId());
                    for(var member:all) {
                        var m=Stations.info(member);
                        if(m.hasUUID("root") && m.getUUID("root").equals(lost)) m.putUUID("root",part.getUniqueId());
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
        // Apply these only after all replacements have recovered their saved state. Both
        // ends of a visit can lose a root together.
        if (!promoted.isEmpty()) for (var peer : Rendezvous.stations(server)) {
            var tag = Stations.info(peer);
            if (tag.hasUUID("host") && promoted.containsKey(tag.getUUID("host")))
                tag.putUUID("host", promoted.get(tag.getUUID("host")));
            var members = tag.getCompound("guests");
            for (var replacement : promoted.entrySet()) {
                String oldId = replacement.getKey().toString();
                if (!members.contains(oldId)) continue;
                members.put(replacement.getValue().toString(), members.get(oldId));
                members.remove(oldId);
            }
            remember(peer, tag);
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
                var core = new BlockPos(st.dX*1024+512,127,st.dZ*1024+512);
                if(orbit==null || Hooks.unresolvedNativeDisk(orbit, core.getX(), core.getZ())
                        || Hooks.pendingMigration(orbit, core.getX(), core.getZ())) continue;
                if (!legacyCoreMissing(orbit,st,core)) continue;
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
        var parent=Stations.nativeStation(original);
        return parent != null && split(part, Stations.info(original), parent.orbiting, checks);
    }
    static void detachAfterLanding(ServerSubLevel previousRoot, OrbitalStation parent, List<ServerSubLevel> landed) {
        var level = previousRoot.getLevel();
        var checks = new Rendezvous.DistanceChecks(level);
        var moved = new HashSet<UUID>();
        for (var part : landed) moved.add(part.getUniqueId());
        var parentTag = landed.getFirst().getUserDataTag().getCompound("sableorbits_landed");
        var rootId = previousRoot.getUniqueId();
        var pending = new ArrayList<ServerSubLevel>();
        for (var part : Stations.loaded(level)) {
            if (part.isRemoved()) continue;
            var tag = Stations.info(part);
            if (!tag.hasUUID("root") && moved.contains(part.getSplitFromSubLevel())) {
                Stations.set(part, parentTag.copy());
                tag = Stations.info(part);
            }
            if (tag.hasUUID("root") && rootId.equals(tag.getUUID("root")) && parent.dX == tag.getInt("x")
                    && parent.dZ == tag.getInt("z")) pending.add(part);
        }
        for (var part : pending) {
            var tag = Stations.info(part);
            if (part.isRemoved() || tag.contains("detachedBody")) continue;
            // Give this connected remainder its own root before attempting to stage it. If
            // staging is vetoed it stays in orbit for a retry even if the parent relaunches.
            for (var member : checks.parts(part)) {
                var data = Stations.info(member);
                if (!data.hasUUID("root")) {
                    Stations.set(member, tag.copy());
                    data = Stations.info(member);
                }
                if (parentTag.hasUUID("owner")) data.putUUID("owner", parentTag.getUUID("owner"));
                data.putUUID("root", part.getUniqueId());
                data.putString("detachedBody", parent.orbiting.name);
            }
            checks.invalidateGroups();
            if (split(part, tag, parent.orbiting, checks)) checks.invalidateTopology();
        }
    }
    private static com.ntmspace.world.SolarSystem.Body landedBody(MinecraftServer server, CompoundTag reservation, CompoundTag fragment) {
        if (reservation.contains("body")) return com.ntmspace.world.SolarSystem.body(reservation.getString("body"));
        // Older reservations saved only the landed hull UUID. A loaded copy of that hull
        // identifies its planet without treating a private orbit's name as a body.
        if (!reservation.hasUUID("id")) return null;
        for (var level : server.getAllLevels()) {
            if (OrbitSpaces.isolated(level)) continue;
            var plots = SubLevelContainer.getContainer(level);
            if (plots == null || !(plots.getSubLevel(reservation.getUUID("id")) instanceof ServerSubLevel landed)) continue;
            var data = landed.getUserDataTag();
            if (data == null) continue;
            var tag = data.getCompound("sableorbits_landed");
            if (tag.hasUUID("root") && tag.getUUID("root").equals(landed.getUniqueId())
                    && tag.getInt("x") == fragment.getInt("x") && tag.getInt("z") == fragment.getInt("z"))
                return Orbit.bodyOf(level);
        }
        return null;
    }
    private static boolean split(ServerSubLevel part, CompoundTag original, com.ntmspace.world.SolarSystem.Body body,
                                 Rendezvous.DistanceChecks checks) {
        var server=part.getLevel().getServer();
        var group=checks.parts(part);
        boolean equipped=computer(group, checks.topology());
        var data=StationData.get(server);
        var addr=freeAddress(data);
        if (addr == null) return false;
        var p=part.logicalPose().position();
        var moved=OrbitSpaces.transfer(group,OrbitSpaces.homeName(part.getLevel(),part.getUniqueId()),
            new Vec3(512-p.x,128-p.y,512-p.z));
        if(moved.isEmpty()) return false;
        var station=data.addStation(addr[0],addr[1],body);
        station.name=(equipped?"Station ":"Space debris ")+addr[0]+", "+addr[1];station.hasStation=true;
        var t=original.copy();
        for(var k:List.of("guests","requests","host","publicVisit","guestLeave","hostLeave","decay","expires","detachedBody","needsHome")) t.remove(k);
        t.putUUID("root",part.getUniqueId());t.putInt("x",addr[0]);t.putInt("z",addr[1]);t.remove(StationAccess.KEY);
        if(!equipped)t.putLong("expires",server.overworld().getGameTime()+6000);
        for(var s:moved)Stations.set(s,t.copy());
        data.setDirty();
        return true;
    }
    static void repairVisits(ServerLevel level) {
        var roots=new HashMap<UUID,ServerSubLevel>();
        for(var s:Stations.loaded(level)) if(Stations.root(s)==s && Stations.nativeStation(s)!=null) roots.put(s.getUniqueId(),s);
        var orphanGroups=new HashMap<UUID,List<ServerSubLevel>>();
        var changed = new HashSet<ServerSubLevel>();
        for(var s:roots.values()) {
            var t=Stations.info(s);
            var guests=t.getCompound("guests");
            for(var id:List.copyOf(guests.getAllKeys())) {
                var guest = roots.get(UUID.fromString(id));
                if (guest == null || guest == s || t.hasUUID("host")) {
                    guests.remove(id);
                    changed.add(s);
                    continue;
                }
                var visit = Stations.info(guest);
                if (visit.hasUUID("host") && !visit.getUUID("host").equals(s.getUniqueId())
                        || !visit.getCompound("guests").isEmpty()) {
                    guests.remove(id);
                    changed.add(s);
                } else if (!visit.hasUUID("host")) {
                    // Legacy host entries recorded membership but not the visitor's consent
                    // mode or approvals. Require both owners when those details were lost.
                    visit.putUUID("host", s.getUniqueId());
                    visit.remove("publicVisit");
                    visit.remove("guestLeave");
                    visit.remove("hostLeave");
                    changed.add(guest);
                }
            }
        }
        for (var guest : roots.values()) {
            var t = Stations.info(guest);
            if (!t.hasUUID("host")) continue;
            var id = t.getUUID("host");
            var host = roots.get(id);
            if (host == null) {
                orphanGroups.computeIfAbsent(id, unused -> new ArrayList<>()).add(guest);
            } else if (host != guest && !Stations.info(host).hasUUID("host")) {
                var guests = Rendezvous.entries(Stations.info(host), "guests");
                if (!guests.contains(guest.getUniqueId().toString())) {
                    guests.putBoolean(guest.getUniqueId().toString(), true);
                    changed.add(host);
                }
            }
        }
        for(var group:orphanGroups.values()) {
            Collections.shuffle(group);var host=group.getFirst();var ht=Stations.info(host);
            for(var s:group) {
                var t=Stations.info(s);
                for(var key:List.of("host","publicVisit","guestLeave","hostLeave")) t.remove(key);
                changed.add(s);
                if(s!=host) {
                    t.putUUID("host",host.getUniqueId());t.putBoolean("publicVisit",!ht.contains(StationAccess.KEY));
                    Rendezvous.entries(ht,"guests").putBoolean(s.getUniqueId().toString(),true);
                }
            }
        }
        changed.forEach(Rendezvous::saveRoot);
    }
}

