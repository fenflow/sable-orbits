package space.sableorbit;

import com.hbm.items.weapon.ItemCustomMissilePart.PartType;
import com.ntmspace.rocket.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.*;
import java.util.*;
import java.util.function.Supplier;

final class Engines {
    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("sableorbits");
    static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,"sableorbits");
    static final Supplier<EngineBlock> BLOCK = BLOCKS.register("rocket_engine", () -> new EngineBlock());
    static final Supplier<BlockEntityType<EngineTile>> TILE = TYPES.register("rocket_engine",
        () -> BlockEntityType.Builder.of(EngineTile::new,BLOCK.get()).build(null));
    static final Supplier<ReactionWheelBlock> WHEEL = BLOCKS.register("reaction_wheel", ReactionWheelBlock::new);
    static final Supplier<ReactionWheelBlock> WHEEL_15 = BLOCKS.register("reaction_wheel_15", () -> new ReactionWheelBlock.Wide(4));
    static final Supplier<ReactionWheelBlock> WHEEL_20 = BLOCKS.register("reaction_wheel_20", () -> new ReactionWheelBlock.Wide(6));
    static final Supplier<BlockEntityType<ReactionWheelTile>> WHEEL_TILE = TYPES.register("reaction_wheel",
        () -> BlockEntityType.Builder.of(ReactionWheelTile::new, WHEEL.get(), WHEEL_15.get(), WHEEL_20.get()).build(null));
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("sableorbits");
    private static final Supplier<BlockItem> WHEEL_ITEM = ITEMS.registerSimpleBlockItem("reaction_wheel", WHEEL);
    private static final Supplier<BlockItem> WHEEL_15_ITEM = ITEMS.registerSimpleBlockItem("reaction_wheel_15", WHEEL_15);
    private static final Supplier<BlockItem> WHEEL_20_ITEM = ITEMS.registerSimpleBlockItem("reaction_wheel_20", WHEEL_20);
    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        TYPES.register(bus);
        ITEMS.register(bus);
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS) {
                event.accept(WHEEL_ITEM.get());
                event.accept(WHEEL_15_ITEM.get());
                event.accept(WHEEL_20_ITEM.get());
            }
        });
        bus.addListener((RegisterCapabilitiesEvent e) -> {
            e.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TILE.get(), EngineTile::fluidInlet);
            e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, TILE.get(), EngineTile::solidItemInlet);
        });
    }
    static List<BlockPos> cells(RocketPart engine, BlockPos inlet, Direction nozzle) {
        int width = width(engine);
        int length = length(engine);
        var u = nozzle.getAxis().isVertical() ? Direction.EAST : nozzle.getClockWise();
        var v = nozzle.getAxis().isVertical() ? Direction.SOUTH : Direction.UP;
        var cells = new ArrayList<BlockPos>();
        for (int h=0; h<length; h++)
            for (int i=0; i<width; i++)
                for (int j=0; j<width; j++)
                    cells.add(inlet.relative(nozzle,h).relative(u,i-width/2).relative(v,j-width/2));
        return cells;
    }
    static int width(RocketPart part) {
        return Math.max(1, (int)Math.ceil(Math.max(part.topRadius(), part.bottomRadius())));
    }
    private static int length(RocketPart part) {
        return Math.max(1, (int)Math.ceil(part.height()));
    }
    static BlockPos reversedInlet(RocketPart engine, BlockPos contact, Direction face) {
        var inlet = contact.relative(face, length(engine) - 1);
        // A horizontal reversal flips the lateral axis. Keep even widths on the same cells.
        return face.getAxis().isHorizontal() && width(engine) % 2 == 0 ? inlet.relative(face.getCounterClockWise()) : inlet;
    }
    static void place(UseItemOnBlockEvent e) {
        if (e.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK || e.getPlayer() == null) return;
        var item = e.getItemStack();
        if (SolidPropellants.units(item) > 0 && e.getLevel().getBlockEntity(e.getPos()) instanceof EngineTile tile) {
            if (!e.getLevel().mayInteract(e.getPlayer(), e.getPos())) return;
            var inlet = tile.solidInlet(e.getFace());
            if (inlet != null && (e.getLevel().isClientSide || inlet.loadSolid(item))) {
                if (!e.getLevel().isClientSide) item.consume(1,e.getPlayer());
                e.setCanceled(true);
                e.setCancellationResult(ItemInteractionResult.sidedSuccess(e.getLevel().isClientSide));
            }
            return;
        }
        var part = RocketParts.get(item);
        if (part == null || part.type() != PartType.THRUSTER) return;
        var player = e.getPlayer();
        var level = e.getLevel();
        var placement = RocketPlacement.of(part, e.getUseOnContext());
        e.cancelWithResult(ItemInteractionResult.FAIL);
        if (!placement.placeable()) return;
        if (level instanceof ServerLevel server) {
            if (!place(server, player, part, placement, e.getFace())) return;
            item.consume(1,player);
        }
        e.setCancellationResult(ItemInteractionResult.sidedSuccess(level.isClientSide));
    }

    private static boolean place(ServerLevel level, Player player, RocketPart part, RocketPlacement placement, Direction face) {
        var nozzle = placement.facing();
        var origin = placement.origin();
        var cells = new ArrayList<>(placement.cells());
        // The first snapshot identifies the cell placed against the clicked face.
        Collections.swap(cells, 0, cells.indexOf(placement.contact()));
        var footprint = Set.copyOf(cells);
        var state = BLOCK.get().defaultBlockState().setValue(EngineBlock.FACING, nozzle);
        var snapshots = new ArrayList<BlockSnapshot>(cells.size());
        var sections = new ArrayList<EngineTile>(cells.size());
        int stagingFlags = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_INVISIBLE;
        for (var cell : cells) {
            var next = cell.relative(nozzle);
            int skin = cell.equals(origin) ? 0 : next.equals(origin) || !footprint.contains(next) ? 2 : 1;
            var snapshot = BlockSnapshot.create(level.dimension(), level, cell, stagingFlags);
            if (!level.setBlock(cell, state.setValue(EngineBlock.SKIN, skin), stagingFlags)) {
                RocketPlacement.restore(snapshots);
                return false;
            }
            snapshots.add(snapshot);
            if (!(level.getBlockEntity(cell) instanceof EngineTile section)) {
                RocketPlacement.restore(snapshots);
                return false;
            }
            section.initialize(part, origin.subtract(cell));
            sections.add(section);
        }
        boolean canceled = snapshots.size() == 1 ? EventHooks.onBlockPlace(player, snapshots.getFirst(), face)
            : EventHooks.onMultiBlockPlace(player, snapshots, face);
        if (canceled || sections.stream().anyMatch(section -> level.getBlockEntity(section.getBlockPos()) != section)) {
            RocketPlacement.restore(snapshots);
            return false;
        }
        for (int index = 0; index < cells.size(); index++) {
            var cell = cells.get(index);
            level.markAndNotifyBlock(cell, level.getChunkAt(cell), snapshots.get(index).getState(),
                sections.get(index).getBlockState(), Block.UPDATE_ALL, Block.UPDATE_LIMIT);
        }
        if (level.getBlockEntity(origin) instanceof EngineTile master) master.refreshPorts();
        return true;
    }
}


