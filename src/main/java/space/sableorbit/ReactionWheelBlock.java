package space.sableorbit;

import com.hbm.api.energymk2.IEnergyConnectorBlock;
import com.hbm.capability.ICapabilityBlock;
import com.hbm.capability.MachineCaps;
import com.hbm.lib.ForgeDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

final class ReactionWheelBlock extends RotatedPillarBlock implements EntityBlock, IEnergyConnectorBlock, ICapabilityBlock {
    private static final MachineCaps CAPS = MachineCaps.blockKeyed().powerIn()
        .faces(ReactionWheelTile.class, (tile, face) -> face != null && face.getAxis() == tile.getBlockState().getValue(AXIS));
    private static final VoxelShape X_SHAPE = Shapes.or(box(0, 4, 0, 16, 12, 16),
        box(0, 0, 4, 16, 16, 12), box(0, 2, 2, 16, 14, 14));
    private static final VoxelShape Y_SHAPE = Shapes.or(box(4, 0, 0, 12, 16, 16),
        box(0, 0, 4, 16, 16, 12), box(2, 0, 2, 14, 16, 14));
    private static final VoxelShape Z_SHAPE = Shapes.or(box(0, 4, 0, 16, 12, 16),
        box(4, 0, 0, 12, 16, 16), box(2, 2, 0, 14, 14, 16));

    ReactionWheelBlock() {
        super(Properties.of().strength(4).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Y));
    }

    @Override public MachineCaps caps() { return CAPS; }
    @Override public boolean canConnect(BlockGetter level, BlockPos pos, ForgeDirection side) {
        return side != null && side != ForgeDirection.UNKNOWN && side.toDirection().getAxis() == level.getBlockState(pos).getValue(AXIS);
    }
    @Override public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return side != null;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(AXIS)) { case X -> X_SHAPE; case Y -> Y_SHAPE; case Z -> Z_SHAPE; };
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ReactionWheelTile(pos, state);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != Engines.WHEEL_TILE.get()) return null;
        return (world, pos, block, tile) -> ((ReactionWheelTile) tile).tick();
    }
}
