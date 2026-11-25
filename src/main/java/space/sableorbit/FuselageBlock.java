package space.sableorbit;

import com.hbm.api.fluidmk2.IFluidConnectorBlockMK2;
import com.hbm.capability.ICapabilityBlock;
import com.hbm.capability.MachineCaps;
import com.hbm.inventory.fluid.FluidType;
import com.hbm.lib.ForgeDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

final class FuselageBlock extends dev.golem.core.world.CompatBlock implements EntityBlock, net.minecraft.world.WorldlyContainerHolder, IFluidConnectorBlockMK2, ICapabilityBlock {
    static final DirectionProperty FACING = BlockStateProperties.FACING;
    static final BooleanProperty SOLID = BooleanProperty.create("solid");
    static final BooleanProperty MASTER = BooleanProperty.create("master");
    // Zero identifies sections written before fuselages had Sable mass properties.
    static final net.minecraft.world.level.block.state.properties.IntegerProperty MASS_CLASS =
        net.minecraft.world.level.block.state.properties.IntegerProperty.create("mass_class", 0, 3);
    private static final MachineCaps CAPS = MachineCaps.blockKeyed().fluidIn().fluidOut().fluidContacts(FuselageTile.class);

    FuselageBlock() {
        super(Properties.of().strength(1.5F, 5).sound(SoundType.METAL).noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.UP).setValue(SOLID, false)
            .setValue(MASTER, true).setValue(MASS_CLASS, 0).setValue(RocketPlacement.FOOTPRINT_ROTATION, Rotation.NONE));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SOLID, MASTER, MASS_CLASS, RocketPlacement.FOOTPRINT_ROTATION);
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return RocketPlacement.rotate(state, rotation); }

    @Override protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(SOLID) ? new SolidFuselageTile(pos, state) : new FuselageTile(pos, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || (type != Fuselages.TILE.get() && type != Fuselages.SOLID_TILE.get())) return null;
        if (!state.getValue(MASTER) && state.getValue(MASS_CLASS) != 0) return null;
        return (world, pos, block, tile) -> {
            var section = (FuselageTile) tile;
            if (section.part() == null) return;
            boolean master = section.isMaster();
            if (block.getValue(MASTER) != master || block.getValue(MASS_CLASS) == 0) {
                world.setBlock(pos, block.setValue(MASTER, master).setValue(MASS_CLASS, Fuselages.massClass(section.part())),
                    Block.UPDATE_CLIENTS);
            }
            if (master) {
                if (world.getGameTime() % 20 == 0) Fuselages.checkFootprint(section);
                if (!section.isRemoved()) section.tick();
            }
        };
    }

    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof FuselageTile section) || section.isMaster()) return;
        if (!level.hasChunkAt(section.masterPos())) {
            level.scheduleTick(pos, this, 20);
            return;
        }
        Fuselages.checkFootprint(section);
        if (!section.isRemoved()) section.refreshFluidPorts();
    }

    @Override protected void onBlockRemoved(BlockState state, Level level, BlockPos pos, boolean moving) {
        if (!(level instanceof ServerLevel server) || moving || !(level.getBlockEntity(pos) instanceof FuselageTile root)
                || !root.isMaster()) return;
        for (var cell : root.cells()) {
            if (!cell.equals(pos) && server.hasChunkAt(cell) && server.getBlockState(cell).is(this)) {
                server.scheduleTick(cell, this, 1);
            }
        }
    }

    @Override public net.minecraft.world.WorldlyContainer getContainer(BlockState state, net.minecraft.world.level.LevelAccessor level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FuselageTile section ? section.inventoryPort() : null;
    }

    @Override public MachineCaps caps() {
        return CAPS;
    }

    @Override public boolean canConnect(FluidType fluid, BlockGetter level, int x, int y, int z, ForgeDirection side) {
        return level.getBlockEntity(new BlockPos(x, y, z)) instanceof FuselageTile section && section.canConnect(fluid, side);
    }

    @Override protected InteractionResult onUseWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof FuselageTile section)) return InteractionResult.PASS;
        var master = section.master();
        if (master == null || master.part() == null) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) master.openMenu(serverPlayer, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected InteractionResult onUseItem(BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        return onUseWithoutItem(state, level, pos, player, hit);
    }

    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof FuselageTile section && section.part() != null
            ? new ItemStack(section.part().item()) : ItemStack.EMPTY;
    }

    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FuselageTile section) {
            var master = section.master();
            if (master != null) Fuselages.dismantle(master, !player.isCreative());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
