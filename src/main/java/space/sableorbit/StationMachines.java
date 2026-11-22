package space.sableorbit;

import com.hbm.api.energymk2.PowerGraph;
import com.hbm.blocks.multiblock.BlockMultiblockCore;
import com.hbm.blocks.multiblock.MultiblockCoreIndex;
import com.hbm.blocks.multiblock.MultiblockSurface;
import com.hbm.blocks.network.BlockCable;
import com.hbm.blocks.network.BlockCablePaintable;
import com.hbm.uninos.graph.LevelNodeGraph;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

final class StationMachines {
    // Plot serialization includes blocks and inventories. HBM keeps cell ownership and HE graphs in level saved data.
    private static final Set<ServerSubLevel> checked = Collections.newSetFromMap(new WeakHashMap<>());

    static void restore(ServerSubLevel part) {
        if (checked.contains(part) || part.getPlot().getLoadedChunks().isEmpty()) return;
        update(part, true);
        checked.add(part);
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
                        if (present && owner != pos.asLong() && !hasLiveOwner(level, cell, owner)) {
                            MultiblockCoreIndex.put(level, cell, pos);
                        } else if (!present && owner == pos.asLong()) {
                            MultiblockCoreIndex.remove(level, cell);
                        } else return;
                        level.invalidateCapabilities(cell);
                        LevelNodeGraph.invalidateEndpointsAround(level, cell);
                    });
                } else if (state.getBlock() instanceof BlockCable || state.getBlock() instanceof BlockCablePaintable) {
                    var graph = PowerGraph.get(level);
                    if (present && !graph.containsCell(pos.asLong())) state.onPlace(level, pos, Blocks.AIR.defaultBlockState(), true);
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
