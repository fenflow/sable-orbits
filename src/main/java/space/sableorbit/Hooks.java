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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class Hooks {
    public static OrbitalStation lookup(MinecraftServer server, int x, int z) {
        if (server == null) return null;
        for (var level : server.getAllLevels()) {
            if (!OrbitSpaces.isolated(level)) continue;
            var part = Sable.HELPER.getContaining(level, new BlockPos(x,0,z));
            if (part instanceof ServerSubLevel s) return Stations.nativeStation(s);
        }
        return null;
    }
    public static boolean computer(ServerPlayer player, BlockPos pos) {
        if (!(Sable.HELPER.getContaining(player.level(), pos) instanceof ServerSubLevel part)) return false;
        var root = Stations.root(part);
        if (root == null || !(player.level().getBlockEntity(pos) instanceof TileEntityOrbitalStationComputer computer)) return false;
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
            var p = part.logicalPose().position();
            var copies = OrbitSpaces.transfer(List.of(part), "station_" + part.getUniqueId(),
                new Vec3(512-p.x,128-p.y,512-p.z), standing);
            var active = copies.isEmpty() ? part : copies.getFirst();
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putUUID("root", part.getUniqueId());
            tag.putInt("x", station.dX);
            tag.putInt("z", station.dZ);
            if (!standing.isEmpty()) tag.putUUID("owner",standing.getFirst().getUUID());
            Stations.set(active,tag);
            station.hasStation = true;
            StationData.get(level.getServer()).setDirty();
        });
    }
    private static final java.util.Map<ServerLevel,Set<BlockPos>> legacy = new WeakHashMap<>();
    public static void queueLegacy(ServerLevel level,BlockPos pos) {
        if (level.dimension().equals(Orbit.ORBIT) && level.getBlockState(pos).is(StationBlocks.ORBITAL_STATION.get())
                && !Sable.HELPER.isInPlotGrid(level,pos)) legacy.computeIfAbsent(level,k -> new LinkedHashSet<>()).add(pos.immutable());
    }
    static void migrateLoaded(ServerLevel level) {
        var positions = legacy.remove(level);
        if (positions == null) return;
        for (var pos : positions) regular(level,pos.getX(),pos.getZ());
    }
    public static boolean travelLocked(MinecraftServer server, OrbitalStation station) {
        return Rendezvous.stations(server).stream().anyMatch(s -> Stations.nativeStation(s) == station && Rendezvous.linked(Stations.info(s)));
    }

    public static OrbitalStation stationInTransfer(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel) || !OrbitSpaces.isolated(level)
                || !(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel part)) return null;
        var station = Stations.nativeStation(part);
        return station != null && station.state != OrbitalStation.StationState.ORBIT ? station : null;
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

