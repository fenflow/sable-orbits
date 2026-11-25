package space.sableorbit;

import com.hbm.api.energymk2.PowerGraph;
import com.hbm.api.energymk2.PowerGraphProvider;
import com.hbm.api.fluidmk2.FluidGraph;
import com.hbm.blocks.multiblock.BlockMultiblockCore;
import com.hbm.blocks.multiblock.MultiblockCoreIndex;
import com.hbm.blocks.multiblock.MultiblockSurface;
import com.hbm.blocks.network.BlockCable;
import com.hbm.blocks.network.BlockCablePaintable;
import com.hbm.blocks.network.WireCoated;
import com.hbm.tileentity.GraphResident;
import com.hbm.tileentity.network.TileEntityCableGauge;
import com.hbm.tileentity.network.TileEntityFluidDuctNT;
import com.hbm.tileentity.network.TileEntityFluidValve;
import com.hbm.uninos.graph.LevelNodeGraph;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

final class StationMachines {
    // Plot serialization includes blocks and inventories. HBM keeps ownership and network graphs per level.
    private static final Set<ServerSubLevel> checked = Collections.newSetFromMap(new WeakHashMap<>());

    static void restore(ServerSubLevel part) {
        if (!checked.contains(part)) restore(List.of(part));
    }

    static void restore(List<ServerSubLevel> parts) {
        var restored = new ArrayList<ServerSubLevel>();
        for (var part : parts) {
            if (checked.contains(part) || part.getPlot().getLoadedChunks().isEmpty()) continue;
            update(part, true);
            restored.add(part);
        }
        // Sable can announce a chunk as full before its block entities exist and can reuse the same
        // chunk coordinates without another promotion. Requeue HBM probes after the whole group is ready.
        for (var part : restored) {
            var level = part.getLevel();
            for (var holder : part.getPlot().getLoadedChunks()) {
                var chunk = holder.getChunk();
                LevelNodeGraph.onChunkFull(level, chunk);
                for (var tile : chunk.getBlockEntities().values()) {
                    if (tile instanceof GraphResident) LevelNodeGraph.invalidateEndpointsAround(level, tile.getBlockPos());
                }
            }
            checked.add(part);
        }
    }

    static void depart(ServerSubLevel part) {
        update(part, false);
        checked.remove(part);
    }

    private static void update(ServerSubLevel part, boolean present) {
        var level = part.getLevel();
        for (var holder : part.getPlot().getLoadedChunks()) {
            for (var tile : holder.getChunk().getBlockEntities().values()) {
                var pos = tile.getBlockPos();
                var state = tile.getBlockState();
                var core = MultiblockSurface.foldedCore(state);
                if (core != null) {
                    core.visitPlacedCells(level, pos, state.getValue(BlockMultiblockCore.FACING), (cell, mask) -> {
                        if (!part.getPlot().contains(new ChunkPos(cell)) || !level.hasChunkAt(cell)
                                || !core.isCellOf(level.getBlockState(cell))) return;
                        long owner = MultiblockCoreIndex.lookup(level, cell);
                        if (present) {
                            if (owner != pos.asLong()) {
                                if (hasLiveOwner(level, cell, owner)) return;
                                MultiblockCoreIndex.put(level, cell, pos);
                            }
                        } else {
                            if (owner != pos.asLong()) return;
                            MultiblockCoreIndex.remove(level, cell);
                        }
                        level.invalidateCapabilities(cell);
                        LevelNodeGraph.invalidateEndpointsAround(level, cell);
                    });
                } else if (state.getBlock() instanceof BlockCable || state.getBlock() instanceof BlockCablePaintable
                        || state.getBlock() instanceof WireCoated) {
                    var graph = PowerGraph.get(level);
                    if (present) graph.addNode(pos.asLong(), PowerGraphProvider.INSTANCE.createData(state), 63);
                    else graph.removeNode(pos.asLong());
                } else if (tile instanceof TileEntityCableGauge) {
                    var graph = PowerGraph.get(level);
                    if (present && !graph.containsCell(pos.asLong())) tile.clearRemoved();
                    if (!present) graph.removeNode(pos.asLong());
                } else if (tile instanceof TileEntityFluidDuctNT || tile instanceof TileEntityFluidValve) {
                    var graph = FluidGraph.get(level);
                    if (present && !graph.containsCell(pos.asLong())) tile.clearRemoved();
                    if (!present) graph.removeNode(pos.asLong());
                }
            }
        }
    }

    private static boolean hasLiveOwner(ServerLevel level, BlockPos cell, long address) {
        if (address == Long.MIN_VALUE) return false;
        var pos = BlockPos.of(address);
        if (!level.hasChunkAt(pos)) return true;
        var state = level.getBlockState(pos);
        var core = MultiblockSurface.foldedCore(state);
        return core != null && core.isCellOf(level.getBlockState(cell))
            && core.claimsCell(level, pos, cell, state.getValue(BlockMultiblockCore.FACING));
    }
}
