package space.sableorbit;

import com.hbm.api.energymk2.IEnergyConnectorBlock;
import com.hbm.capability.ICapabilityBlock;
import com.hbm.capability.MachineCaps;
import com.hbm.lib.ForgeDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class ReactionWheelBlock extends RotatedPillarBlock implements EntityBlock, IEnergyConnectorBlock, ICapabilityBlock {
    static final IntegerProperty SECTION = IntegerProperty.create("section", 0, 3);
    static final EnumProperty<Rotation> FOOTPRINT_ROTATION = EnumProperty.create("footprint_rotation", Rotation.class);
    private static final MachineCaps CAPS = MachineCaps.blockKeyed().powerIn()
        .faces(ReactionWheelTile.class, (tile, face) -> face != null && face.getAxis() == tile.getBlockState().getValue(AXIS));
    private static final VoxelShape X_SHAPE = Shapes.or(box(0, 4, 0, 16, 12, 16),
        box(0, 0, 4, 16, 16, 12), box(0, 2, 2, 16, 14, 14));
    private static final VoxelShape Y_SHAPE = Shapes.or(box(4, 0, 0, 12, 16, 16),
        box(0, 0, 4, 16, 16, 12), box(2, 0, 2, 14, 16, 14));
    private static final VoxelShape Z_SHAPE = Shapes.or(box(0, 4, 0, 16, 12, 16),
        box(4, 0, 0, 12, 16, 16), box(2, 2, 0, 14, 14, 16));

    private final boolean wide;
    private final int motorStrength;

    ReactionWheelBlock() {
        this(false, 1);
    }

    private ReactionWheelBlock(boolean wide, int motorStrength) {
        super(properties(wide));
        this.wide = wide;
        this.motorStrength = motorStrength;
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Y));
    }

    static final class Wide extends ReactionWheelBlock {
        Wide(int motorStrength) {
            super(true, motorStrength);
            registerDefaultState(defaultBlockState().setValue(SECTION, 0).setValue(FOOTPRINT_ROTATION, Rotation.NONE));
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(SECTION, FOOTPRINT_ROTATION);
        }

        @Override protected BlockState rotate(BlockState state, Rotation rotation) {
            return super.rotate(state, rotation).setValue(FOOTPRINT_ROTATION, state.getValue(FOOTPRINT_ROTATION).getRotated(rotation));
        }
    }

    private static Properties properties(boolean wide) {
        var properties = Properties.of().strength(4).sound(SoundType.METAL).noOcclusion();
        return wide ? properties.noLootTable().pushReaction(PushReaction.BLOCK) : properties;
    }
    boolean wide() { return wide; }
    int motorStrength() { return motorStrength; }

    static ReactionWheelBlock wideWheel(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ReactionWheelBlock wheel && wheel.wide
            ? wheel : null;
    }

    static void place(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK || event.getPlayer() == null) return;
        var wheel = wideWheel(event.getItemStack());
        if (wheel == null) return;
        var placement = RocketPlacement.wheel(wheel, event.getUseOnContext());
        event.cancelWithResult(ItemInteractionResult.FAIL);
        if (!placement.placeable()) return;
        var level = event.getLevel();
        if (level instanceof ServerLevel server) {
            if (!wheel.place(server, event.getPlayer(), placement)) return;
            event.getItemStack().consume(1, event.getPlayer());
        }
        event.setCancellationResult(ItemInteractionResult.sidedSuccess(level.isClientSide));
    }

    private boolean place(ServerLevel level, Player player, RocketPlacement placement) {
        var sections = placement.cells();
        var state = defaultBlockState().setValue(AXIS, placement.facing().getAxis());
        var snapshots = new ArrayList<BlockSnapshot>(sections.size());
        var states = new ArrayList<BlockState>(sections.size());
        int flags = UPDATE_KNOWN_SHAPE | UPDATE_INVISIBLE;
        for (int index = 0; index < sections.size(); index++) {
            var pos = sections.get(index);
            var section = state.setValue(SECTION, index);
            var snapshot = BlockSnapshot.create(level.dimension(), level, pos, flags);
            if (!level.setBlock(pos, section, flags)) {
                RocketPlacement.restore(snapshots);
                return false;
            }
            snapshots.add(snapshot);
            states.add(section);
        }
        // NeoForge uses the first snapshot to identify the support block.
        var eventSnapshots = new ArrayList<>(snapshots);
        Collections.swap(eventSnapshots, 0, sections.indexOf(placement.contact()));
        if (EventHooks.onMultiBlockPlace(player, eventSnapshots, placement.facing())) {
            RocketPlacement.restore(snapshots);
            return false;
        }
        for (int index = 0; index < sections.size(); index++) {
            var pos = sections.get(index);
            if (level.getBlockState(pos) != states.get(index) || !(level.getBlockEntity(pos) instanceof ReactionWheelTile)) {
                RocketPlacement.restore(snapshots);
                return false;
            }
        }
        for (int index = 0; index < sections.size(); index++) {
            var pos = sections.get(index);
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), snapshots.get(index).getState(), states.get(index), UPDATE_ALL, UPDATE_LIMIT);
        }
        var sound = state.getSoundType(level, placement.contact(), player);
        level.playSound(null, placement.contact(), sound.getPlaceSound(), SoundSource.BLOCKS,
            (sound.getVolume() + 1) / 2, sound.getPitch() * .8F);
        level.gameEvent(GameEvent.BLOCK_PLACE, placement.contact(), GameEvent.Context.of(player, state));
        return true;
    }

    static Direction.Axis originalAxis(BlockState state) {
        // For an unsigned axis, a quarter turn and its inverse swap X and Z identically.
        var direction = Direction.get(Direction.AxisDirection.POSITIVE, state.getValue(AXIS));
        return state.getValue(FOOTPRINT_ROTATION).rotate(direction).getAxis();
    }

    static BlockPos sectionOffset(BlockState state, int section) {
        if (section == 0) return BlockPos.ZERO;
        int first = section & 1;
        int second = section >> 1;
        var offset = switch (originalAxis(state)) {
            case X -> new BlockPos(0, second, first);
            case Y -> new BlockPos(first, 0, second);
            case Z -> new BlockPos(first, second, 0);
        };
        return offset.rotate(state.getValue(FOOTPRINT_ROTATION));
    }

    List<BlockPos> cells(BlockPos root, Direction.Axis axis) {
        return cells(root, defaultBlockState().setValue(AXIS, axis));
    }

    List<BlockPos> cells(BlockPos root, BlockState state) {
        if (!wide) return List.of(root);
        return List.of(root, root.offset(sectionOffset(state, 1)), root.offset(sectionOffset(state, 2)),
            root.offset(sectionOffset(state, 3)));
    }

    @Override public MachineCaps caps() { return CAPS; }
    @Override public boolean canConnect(BlockGetter level, BlockPos pos, ForgeDirection side) {
        return side != null && side != ForgeDirection.UNKNOWN && side.toDirection().getAxis() == level.getBlockState(pos).getValue(AXIS);
    }
    @Override public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return side != null;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (wide) return Shapes.block();
        return switch (state.getValue(AXIS)) { case X -> X_SHAPE; case Y -> Y_SHAPE; case Z -> Z_SHAPE; };
    }
    @Override protected RenderShape getRenderShape(BlockState state) {
        return wide ? RenderShape.ENTITYBLOCK_ANIMATED : super.getRenderShape(state);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ReactionWheelTile(pos, state);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != Engines.WHEEL_TILE.get() || (wide && state.getValue(SECTION) != 0)) return null;
        return (world, pos, block, tile) -> ((ReactionWheelTile) tile).tick();
    }

    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (wide && level instanceof ServerLevel && level.getBlockEntity(pos) instanceof ReactionWheelTile section) {
            var master = section.master();
            if (master != null) {
                if (!player.isCreative()) popResource(level, pos, new ItemStack(this));
                master.dismantle();
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (wide && !next.is(this) && !moving && state.getValue(SECTION) == 0 && level instanceof ServerLevel server) {
            for (var cell : cells(pos, state)) {
                if (!cell.equals(pos) && server.hasChunkAt(cell) && server.getBlockState(cell).is(this)) {
                    server.scheduleTick(cell, this, 1);
                }
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }

    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof ReactionWheelTile section) || section.isMaster()) return;
        if (!level.hasChunkAt(section.masterPos())) level.scheduleTick(pos, this, 20);
        else if (section.master() == null) level.removeBlock(pos, false);
    }
}
