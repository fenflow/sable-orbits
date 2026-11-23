package space.sableorbit;

import com.hbm.inventory.fluid.Fluids;
import com.hbm.items.weapon.ItemCustomMissilePart.PartType;
import com.ntmspace.rocket.RocketPart;
import com.ntmspace.rocket.RocketParts;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.minecraft.world.ItemInteractionResult;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

final class Fuselages {
    private static final String ITEM_TANKS = "sableorbits_fuselage_tanks";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("sableorbits");
    private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "sableorbits");
    static final Supplier<FuselageBlock> BLOCK = BLOCKS.register("rocket_fuselage", FuselageBlock::new);
    static final Supplier<BlockEntityType<FuselageTile>> TILE = TYPES.register("rocket_fuselage",
        () -> BlockEntityType.Builder.of(FuselageTile::new, BLOCK.get()).build(null));
    static final Supplier<BlockEntityType<SolidFuselageTile>> SOLID_TILE = TYPES.register("solid_rocket_fuselage",
        () -> BlockEntityType.Builder.of(SolidFuselageTile::new, BLOCK.get()).build(null));

    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        TYPES.register(bus);
        FuselageMenu.MENUS.register(bus);
        SolidFuselageMenu.MENUS.register(bus);
        bus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TILE.get(), FuselageTile::fluidHandler);
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, SOLID_TILE.get(), FuselageTile::itemHandler);
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, TILE.get(), FuselageTile::itemHandler);
        });
    }

    static boolean isLiquidFuselage(RocketPart part) {
        return part != null && part.type() == PartType.FUSELAGE && part.getTankSize() > 0
            && ("ANY".equals(part.fuel()) || part.getFuel() != null && part.getFuel() != Fluids.NONE);
    }

    static boolean isSolidFuselage(RocketPart part) {
        return part != null && part.type() == PartType.FUSELAGE && part.getTankSize() > 0 && "SOLID".equals(part.fuel());
    }

    static int capacity(RocketPart part) {
        return part.getTankSize() * 10;
    }

    static int massClass(RocketPart part) {
        double width = Math.max(part.topRadius(), part.bottomRadius());
        return width <= 1 ? 1 : width <= 1.5 ? 2 : 3;
    }

    static void tooltip(ItemTooltipEvent event) {
        var part = RocketParts.get(event.getItemStack());
        if (isLiquidFuselage(part)) {
            event.getToolTip().add(Component.translatable("tooltip.sableorbits.fuselage.capacity", capacity(part))
                .withStyle(ChatFormatting.GRAY));
        } else if (isSolidFuselage(part)) {
            event.getToolTip().add(Component.translatable("tooltip.sableorbits.fuselage.solid_capacity", capacity(part))
                .withStyle(ChatFormatting.GRAY));
        }
    }

    static void place(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK || event.getPlayer() == null) return;
        var item = event.getItemStack();
        var part = RocketParts.get(item);
        if ((!isLiquidFuselage(part) && !isSolidFuselage(part))) return;

        var level = event.getLevel();
        var player = event.getPlayer();
        var placement = RocketPlacement.of(part, event.getUseOnContext());
        event.cancelWithResult(ItemInteractionResult.FAIL);
        if (!placement.placeable()) return;
        var facing = placement.facing();
        var origin = placement.origin();
        var cells = new ArrayList<>(placement.cells());
        // NeoForge uses the first snapshot to find the support block.
        Collections.swap(cells, 0, cells.indexOf(origin));
        if (level instanceof ServerLevel server) {
            BlockState state = BLOCK.get().defaultBlockState().setValue(FuselageBlock.FACING, facing)
                .setValue(FuselageBlock.SOLID, isSolidFuselage(part)).setValue(FuselageBlock.MASS_CLASS, massClass(part));
            if (!place(server, player, origin, facing, cells, state, part, item)) return;
            item.consume(1, player);
        }
        event.setCancellationResult(ItemInteractionResult.sidedSuccess(level.isClientSide));
    }

    private static boolean place(ServerLevel level, Player player, BlockPos origin, Direction facing,
                                 List<BlockPos> cells, BlockState state, RocketPart part, ItemStack item) {
        int stagingFlags = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_INVISIBLE;
        var replaced = new ArrayList<BlockSnapshot>(cells.size());
        var sections = new ArrayList<FuselageTile>(cells.size());
        for (var cell : cells) {
            var snapshot = BlockSnapshot.create(level.dimension(), level, cell, stagingFlags);
            if (!level.setBlock(cell, state, stagingFlags)) {
                restore(replaced);
                return false;
            }
            replaced.add(snapshot);
            if (!(level.getBlockEntity(cell) instanceof FuselageTile section)) {
                restore(replaced);
                return false;
            }
            section.initialize(part, origin.subtract(cell));
            sections.add(section);
        }

        if (EventHooks.onMultiBlockPlace(player, replaced, facing)
                || sections.stream().anyMatch(section -> level.getBlockEntity(section.getBlockPos()) != section)) {
            restore(replaced);
            return false;
        }

        var master = (FuselageTile) level.getBlockEntity(origin);
        var contents = item.get(DataComponents.CUSTOM_DATA);
        if (contents != null) master.loadTankContents(contents.copyTag().getCompound(ITEM_TANKS));
        for (int index = 0; index < cells.size(); index++) {
            BlockPos cell = cells.get(index);
            level.markAndNotifyBlock(cell, level.getChunkAt(cell), replaced.get(index).getState(), state, Block.UPDATE_ALL, Block.UPDATE_LIMIT);
            sections.get(index).refreshFluidPorts();
        }
        return true;
    }

    private static void restore(List<BlockSnapshot> snapshots) {
        for (var snapshot : snapshots.reversed()) snapshot.restore(Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
    }

    static void checkFootprint(FuselageTile section) {
        var level = section.getLevel();
        var part = section.part();
        if (part == null) return;
        BlockPos pos = section.getBlockPos();
        if (!section.isMaster()) {
            if (level.hasChunkAt(section.masterPos()) && section.master() == null) level.removeBlock(pos, false);
            return;
        }

        for (var cell : section.cells()) {
            if (level.hasChunkAt(cell) && !(level.getBlockEntity(cell) instanceof FuselageTile piece && piece.master() == section)) {
                dismantle(section, true);
                return;
            }
        }
    }

    static void dismantle(FuselageTile master, boolean drop) {
        var level = master.getLevel();
        var part = master.part();
        if (part == null) return;
        var pos = master.getBlockPos();
        if (drop) {
            var item = new ItemStack(part.item());
            var contents = master.saveTankContents();
            if (!contents.isEmpty()) CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> tag.put(ITEM_TANKS, contents));
            Block.popResource(level, pos, item);
        }
        for (var cell : master.cells()) {
            if (!cell.equals(pos) && level.hasChunkAt(cell)
                && level.getBlockEntity(cell) instanceof FuselageTile piece && piece.master() == master) level.removeBlock(cell, false);
        }
        level.removeBlock(pos, false);
    }
}
