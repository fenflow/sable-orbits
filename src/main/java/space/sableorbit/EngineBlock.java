package space.sableorbit;

import com.hbm.api.fluidmk2.IFluidConnectorBlockMK2;
import com.hbm.inventory.fluid.FluidType;
import com.hbm.lib.ForgeDirection;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;

final class EngineBlock extends Block implements EntityBlock, WorldlyContainerHolder, IFluidConnectorBlockMK2, com.hbm.capability.ICapabilityBlock {
    private static final com.hbm.capability.MachineCaps CAPS = com.hbm.capability.MachineCaps.blockKeyed().fluidIn().fluidContacts(EngineTile.class);
    @Override public com.hbm.capability.MachineCaps caps() { return CAPS; }
    static final DirectionProperty FACING = BlockStateProperties.FACING;
    static final IntegerProperty SKIN = IntegerProperty.create("section",0,2);
    EngineBlock() {
        super(Properties.of().strength(1.5F, 5).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(defaultBlockState().setValue(FACING,Direction.DOWN).setValue(SKIN,0));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(FACING,SKIN); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.ENTITYBLOCK_ANIMATED; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new EngineTile(pos,state); }
    @Override public WorldlyContainer getContainer(BlockState state, LevelAccessor level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof EngineTile tile ? tile.solidContainer() : null;
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != Engines.TILE.get() || state.getValue(SKIN) != 0) return null;
        if (level.isClientSide) return (l,p,s,t) -> EngineEffects.tick((EngineTile)t);
        return (l,p,s,t) -> ((EngineTile)t).tick();
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof EngineTile section) section.checkSection(level);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (level instanceof ServerLevel server && !next.is(this) && !moving
                && level.getBlockEntity(pos) instanceof EngineTile tile && tile.isMaster()) tile.checkRemainingSections(server);
        super.onRemove(state, level, pos, next, moving);
    }
    @Override public net.minecraft.world.item.ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof EngineTile tile && tile.engine()!=null
            ? new net.minecraft.world.item.ItemStack(tile.engine().item()) : net.minecraft.world.item.ItemStack.EMPTY;
    }
    @Override public boolean canConnect(FluidType fluid, BlockGetter level, int x, int y, int z, ForgeDirection side) {
        return level.getBlockEntity(new BlockPos(x,y,z)) instanceof EngineTile tile && tile.canConnect(fluid,side);
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof EngineTile tile) tile.dismantle(!player.isCreative());
        return super.playerWillDestroy(level,pos,state,player);
    }
}


