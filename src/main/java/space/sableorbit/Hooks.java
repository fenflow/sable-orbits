package space.sableorbit;

import com.ntmspace.orbit.*;
import com.ntmspace.registry.StationBlocks;
import com.ntmspace.tileentity.station.TileEntityOrbitalStationComputer;
import com.hbm.blocks.multiblock.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.*;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class Hooks {
    public static OrbitalStation stationAtPlot(ServerSubLevel part) { return Stations.nativeStation(part); }

    public static OrbitalStation lookup(MinecraftServer server, int x, int z) {
        if (server == null) return null;
        for (var level : server.getAllLevels()) {
            if (!OrbitSpaces.isolated(level) && !level.dimension().equals(Orbit.ORBIT)) continue;
            var part = Sable.HELPER.getContaining(level, new BlockPos(x,0,z));
            if (part instanceof ServerSubLevel s) return Stations.nativeStation(s);
        }
        return null;
    }
    public static boolean computer(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof TileEntityOrbitalStationComputer computer)
                || !PinMenu.inRange(player, computer)) return false;
        var location = StationAccess.locate(computer);
        var root = location.root();
        var tag = location.tag();
        if (root == null && !tag.contains(StationAccess.KEY)) return false;
        var placement = StationAccess.placement(computer);
        if (!tag.getCompound(StationAccess.KEY).hasUUID("owner") && placement.hasUUID("owner")) tag.put(StationAccess.KEY, placement.copy());
        UUID founder = tag.hasUUID("owner") ? tag.getUUID("owner") : player.getUUID();
        var access = StationAccess.claim(tag, founder, player.getServer().overworld().getGameTime());
        if (!StationAccess.configured(access) && player.getUUID().equals(access.getUUID("owner"))) {
            if (root != null) Rendezvous.saveRoot(root);
            PinMenu.open(player, computer, root, null);
            return true;
        }
        if (root == null) return false;
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new Console(id, player, root, computer), Component.literal("Orbital station computer")));
        return true;
    }
    public static void regular(ServerLevel level, int x, int z) {
        if (!level.dimension().equals(Orbit.ORBIT)) return;
        var station = OrbitalStation.getStationFromPosition(level.getServer(), x, z);
        assembleRegular(level,station,new BlockPos(x,127,z));
    }
    static void assembleRegular(ServerLevel level,OrbitalStation station,BlockPos pos) {
        var core = StationBlocks.ORBITAL_STATION.get();
        if (!level.getBlockState(pos).is(core)) return;
        var cells = new LinkedHashSet<BlockPos>();
        cells.add(pos);
        core.visitPlacedCells(level, pos, Direction.NORTH, (cell, mask) -> cells.add(cell.immutable()));
        var computer = StationBlocks.ORBITAL_STATION_COMPUTER.get();
        var top = pos.above(1 + cells.stream().mapToInt(BlockPos::getY).max().orElse(127) - pos.getY());
        var planned = new LinkedHashSet<BlockPos>();
        planned.add(top);
        computer.visitPlacementCells(top,Direction.NORTH,net.minecraft.world.item.ItemStack.EMPTY,(cell,mask) -> planned.add(cell.immutable()));
        if (planned.stream().allMatch(level::isEmptyBlock)) MultiblockHandlerXR.placeAssembled(level,top,computer,Direction.NORTH);
        if (level.getBlockState(top).is(computer)) cells.addAll(planned);
        var queue = new ArrayDeque<BlockPos>();
        if (level.dimension().equals(Orbit.ORBIT)) queue.addAll(cells);
        while (!queue.isEmpty()) {
            var next = queue.removeFirst();
            for (var face : Direction.values()) {
                var neighbor = next.relative(face);
                if (OrbitalStation.gridOf(neighbor.getX()) != OrbitalStation.gridOf(pos.getX())
                        || OrbitalStation.gridOf(neighbor.getZ()) != OrbitalStation.gridOf(pos.getZ())) continue;
                if (cells.contains(neighbor) || level.isOutsideBuildHeight(neighbor) || level.isEmptyBlock(neighbor)) continue;
                cells.add(neighbor);
                queue.addLast(neighbor);
            }
        }
        cells.removeIf(level::isEmptyBlock);
        var standing = level.players().stream().filter(p -> cells.contains(p.getOnPos())).toList();
        BlockMultiblockCore.withoutTeardown(() -> {
            var part = SubLevelAssemblyHelper.assembleBlocks(level, pos, cells, BoundingBox3i.from(cells));
            if (part == null) return;
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putUUID("root", part.getUniqueId());
            tag.putInt("x", station.dX);
            tag.putInt("z", station.dZ);
            if (!standing.isEmpty()) tag.putUUID("owner",standing.getFirst().getUUID());
            var access = StationAccess.earliest(List.of(part));
            if (access != null) {
                tag.put(StationAccess.KEY, access.copy());
                tag.putUUID("owner", access.getUUID("owner"));
            }
            StationAccess.clearPreflight(part);
            // Save the disk identity on the assembled source before any passenger can veto travel.
            Stations.set(part,tag);
            station.hasStation = true;
            StationData.get(level.getServer()).setDirty();
            transferLegacy(part, station, standing);
        });
    }
    private static final Map<ServerLevel,Set<BlockPos>> legacy = new WeakHashMap<>();
    private static final Map<ServerSubLevel,Long> retryMigrationAfter = new WeakHashMap<>();
    private static final Map<ServerLevel,Set<OrbitalStation>> oldNativeDisks = new WeakHashMap<>();
    private static final TicketType<ChunkPos> LEGACY_ARRIVAL =
        TicketType.create("sableorbits_legacy_arrival", Comparator.comparingLong(p -> p.toLong()), 160);

    static void watchNativeMigrations(ServerLevel level) {
        if (level == null || !level.dimension().equals(Orbit.ORBIT) || oldNativeDisks.containsKey(level)) return;
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        Set<OrbitalStation> candidates = Collections.newSetFromMap(new WeakHashMap<>());
        oldNativeDisks.put(level, candidates);
        if (!container.getOccupancy().isEmpty()) {
            var addresses = OrbitSpaces.data(level.getServer()).addresses;
            for (var station : StationData.get(level.getServer()).getStations())
                if (station.hasStation && !addresses.contains(station.dX + "," + station.dZ))
                    candidates.add(station);
        }
        container.addObserver(new dev.ryanhcode.sable.api.sublevel.SubLevelObserver() {
            @Override public void onSubLevelRemoved(dev.ryanhcode.sable.sublevel.SubLevel removed,
                    dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason reason) {
                if (reason != dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED
                        || !(removed instanceof ServerSubLevel part)) return;
                var info = Stations.info(part);
                if (!info.hasUUID("root") || !info.getUUID("root").equals(part.getUniqueId())) return;
                int x = info.getInt("x"), z = info.getInt("z");
                candidates.remove(StationData.get(level.getServer()).getStationFromPosition(x*1024,z*1024));
                // Sable's ticket observer already clears every ticket on REMOVED and retains them on UNLOADED.
                retryMigrationAfter.remove(part);
            }
        });
    }

    static boolean unresolvedNativeDisk(ServerLevel level, int x, int z) {
        var candidates = oldNativeDisks.get(level);
        if (candidates == null || candidates.isEmpty() || !candidates.contains(StationData.get(level.getServer()).getStationFromPosition(x,z))) return false;
        // Old plots have no migration ticket or global UUID/address index. Their storage
        // pointer names a physical holding chunk, which can be far from the original core.
        var container = SubLevelContainer.getContainer(level);
        return container != null && container.getOccupancy().cardinality() > container.getLoadedCount();
    }

    public static boolean waitForLegacySpawn(ServerLevel level, int x, int z) {
        if (pendingMigration(level,x,z)) return true;
        if (!unresolvedNativeDisk(level,x,z)) return false;
        var chunk = new ChunkPos(new BlockPos(OrbitalStation.gridOf(x)*1024+512,127,OrbitalStation.gridOf(z)*1024+512));
        level.getChunkSource().addRegionTicket(LEGACY_ARRIVAL, chunk, 3, chunk);
        return true;
    }

    private static String migrationTicket(int gridX, int gridZ) {
        return "migration/" + gridX + "/" + gridZ;
    }

    public static boolean pendingMigration(ServerLevel level, int worldX, int worldZ) {
        if (!level.dimension().equals(Orbit.ORBIT)) return false;
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        int gridX = OrbitalStation.gridOf(worldX), gridZ = OrbitalStation.gridOf(worldZ);
        String key = migrationTicket(gridX, gridZ);
        for (var entry : container.getAllTickets().values()) {
            for (var ticket : entry.tickets())
                if (ticket.type() == Stations.TICKET && key.equals(ticket.key())) return true;
        }
        // Older stalled conversions have the root tag but predate the source loading ticket.
        for (var part : container.getAllSubLevels()) {
            var info = Stations.info(part);
            if (!part.isRemoved() && info.hasUUID("root") && part.getUniqueId().equals(info.getUUID("root"))
                    && info.getInt("x") == gridX && info.getInt("z") == gridZ) return true;
        }
        return false;
    }

    private static void transferLegacy(ServerSubLevel part, OrbitalStation station, List<ServerPlayer> standing) {
        var source = part.getLevel();
        watchNativeMigrations(source);
        var p = part.logicalPose().position();
        var moved = OrbitSpaces.transfer(List.of(part), "station_" + part.getUniqueId(),
            new Vec3(512-p.x,128-p.y,512-p.z), standing);
        if (moved.isEmpty()) {
            var aboard = new LinkedHashSet<>(standing);
            aboard.addAll(Flight.crew(List.of(part)));
            for (var player : aboard) LegacyCrew.remember(player, part, station);
            SubLevelContainer.getContainer(source).addForceLoadTicket(part, Stations.TICKET, migrationTicket(station.dX, station.dZ));
            // Retry a transient refusal promptly. Sustained vetoes get a longer interval.
            retryMigrationAfter.put(part, source.getGameTime() + (retryMigrationAfter.containsKey(part) ? 200 : 20));
        }
    }
    public static void queueLegacy(ServerLevel level,BlockPos pos) {
        if (level.dimension().equals(Orbit.ORBIT) && level.getBlockState(pos).is(StationBlocks.ORBITAL_STATION.get())
                && !Sable.HELPER.isInPlotGrid(level,pos)) legacy.computeIfAbsent(level,k -> new LinkedHashSet<>()).add(pos.immutable());
    }
    static void migrateLoaded(ServerLevel level) {
        var positions = legacy.remove(level);
        if (positions != null) for (var pos : positions) regular(level,pos.getX(),pos.getZ());
        if (level.getGameTime() % 20 != 0) return;
        for (var part : Stations.loaded(level)) {
            if (part.isRemoved() || Stations.root(part) != part || retryMigrationAfter.getOrDefault(part, 0L) > level.getGameTime()) continue;
            var station = Stations.nativeStation(part);
            if (station == null || !station.hasStation || StationLife.reserved(level.getServer(), station.dX, station.dZ)
                    || Docking.addressed(level.getServer(), station.dX*1024, station.dZ*1024) != null) continue;
            BlockMultiblockCore.withoutTeardown(() -> transferLegacy(part, station, List.of()));
        }
        LegacyCrew.retry(level);
    }
    public static boolean travelLocked(MinecraftServer server, OrbitalStation station) {
        return Rendezvous.stations(server).stream().anyMatch(s -> Stations.nativeStation(s) == station && Rendezvous.linked(Stations.info(s)));
    }

    public static List<Propulsion> refreshTravelEquipment(ServerLevel level, OrbitalStation station) {
        if (!OrbitSpaces.isolated(level)) return List.of();
        for (var part : Stations.loaded(level)) {
            if (Stations.root(part) == part && Stations.nativeStation(part) == station)
                return Stations.refreshEquipment(station, Flight.group(part));
        }
        return List.of();
    }

    public static OrbitalStation stationInTransfer(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel) || !OrbitSpaces.isolated(level)
                || !(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel part)) return null;
        var station = Stations.nativeStation(part);
        return station != null && station.state != OrbitalStation.StationState.ORBIT ? station : null;
    }

    public static boolean landedStation(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || !(Sable.HELPER.getContaining(server, pos) instanceof ServerSubLevel plot)
                || plot.isRemoved()) return false;
        var data = plot.getUserDataTag();
        return data != null && data.contains("sableorbits_landed", Tag.TAG_COMPOUND)
            && data.getCompound("sableorbits_landed").hasUUID("root");
    }

    // Size and thrust belong to the burn that launched the station. A thruster
    // can detach or unload during the turn, before NTM asks for the cruise time.
    public static int mobileCruiseTime(ServerLevel level, OrbitalStation station, float thrust, boolean beginBurn) {
        for (var part : Stations.loaded(level)) {
            if (Stations.root(part) != part || Stations.nativeStation(part) != station) continue;
            var info = Stations.info(part);
            if (!beginBurn && info.getInt("cruiseTicks") > 0) return info.getInt("cruiseTicks");
            if (thrust <= 0) return 0;
            var bounds = Rendezvous.bounds(part);
            // Native calculateSize includes a 9 by 9 area around the port.
            int footprint = (int)(Math.max(9,Math.ceil(bounds.getXsize())) * Math.max(9,Math.ceil(bounds.getZsize())));
            double distance = com.ntmspace.world.SolarSystem.calculateDistanceBetweenTwoBodies(
                level.getGameTime(),station.orbiting,station.target);
            int duration = Math.max(1,OrbitalStation.calculateTransferTime(distance,footprint,thrust));
            if (beginBurn) info.putInt("cruiseTicks",duration);
            return duration;
        }
        return 0;
    }
    public static boolean occupiedElsewhere(ServerLevel source, int index) {
        for (var level : source.getServer().getAllLevels()) {
            if (level == source) continue;
            var container = SubLevelContainer.getContainer(level);
            if (container != null && container.getOccupancy().get(index)) return true;
        }
        return false;
    }
}

