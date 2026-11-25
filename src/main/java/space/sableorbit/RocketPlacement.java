package space.sableorbit;

import com.hbm.items.weapon.ItemCustomMissilePart.PartType;
import com.ntmspace.rocket.RocketPart;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import java.util.List;

record RocketPlacement(BlockPos origin, BlockPos contact, Direction facing, List<BlockPos> cells, boolean placeable) {
    // Sable rotates block states and positions but copies the original section offset NBT.
    static final EnumProperty<Rotation> FOOTPRINT_ROTATION = EnumProperty.create("footprint_rotation", Rotation.class);

    static BlockState rotate(BlockState state, Rotation turn) {
        return state.setValue(BlockStateProperties.FACING, turn.rotate(state.getValue(BlockStateProperties.FACING)))
            .setValue(FOOTPRINT_ROTATION, state.getValue(FOOTPRINT_ROTATION).getRotated(turn));
    }

    static org.joml.Quaternionf modelRotation(BlockState state, Direction mountingFace) {
        var turn = state.getValue(FOOTPRINT_ROTATION);
        if (turn == Rotation.NONE) return mountingFace.getRotation();
        // Preserve the roll around the mounting axis, including the bell layout of an upright engine.
        var originalFace = turn.rotation().inverse().rotate(mountingFace);
        return new org.joml.Quaternionf().rotationY((float)(-Math.PI / 2) * turn.rotate(0, 4)).mul(originalFace.getRotation());
    }

    static RocketPlacement of(RocketPart part, UseOnContext use) {
        var context = new BlockPlaceContext(use);
        // Sable's block hit already reports the face in plot coordinates.
        Direction face = use.getClickedFace();
        Direction facing = face;
        BlockPos contact = context.getClickedPos();
        BlockPos origin = contact;
        if (part.type() == PartType.THRUSTER && use.isSecondaryUseActive()) {
            facing = face.getOpposite();
            origin = Engines.reversedInlet(part, contact, face);
        }
        var cells = Engines.cells(part, origin, facing);
        return new RocketPlacement(origin, contact, facing, cells, canPlace(context, use.getClickedPos(), cells));
    }

    static RocketPlacement wheel(ReactionWheelBlock block, UseOnContext use) {
        var context = new BlockPlaceContext(use);
        var level = use.getLevel();
        var contact = context.getClickedPos();
        var face = use.getClickedFace();
        var axis = face.getAxis();
        var origin = wheelOrigin(use, contact);
        var cells = block.cells(origin, axis);
        var state = block.defaultBlockState().setValue(ReactionWheelBlock.AXIS, axis);
        var collision = CollisionContext.of(use.getPlayer());
        boolean valid = canPlace(context, use.getClickedPos(), cells)
            && cells.stream().allMatch(pos -> level.isUnobstructed(state, pos, collision));
        return new RocketPlacement(origin, contact, face, cells, valid);
    }

    private static BlockPos wheelOrigin(UseOnContext use, BlockPos contact) {
        var face = use.getClickedFace();
        var axis = face.getAxis();
        var support = use.getLevel().getBlockEntity(use.getClickedPos());
        if (support instanceof ReactionWheelTile wheel && wheel.block().wide() && wheel.axis() == axis) {
            var master = wheel.master();
            if (master != null) return master.minimumCorner().relative(face);
        }
        if (support instanceof FuselageTile fuselage && fuselage.isEnd(face) && Engines.width(fuselage.part()) == 2) {
            var master = fuselage.master();
            if (master != null) {
                var corner = minimum(master.cells().getFirst(), master.cells().getLast());
                return switch (axis) {
                    case X -> new BlockPos(contact.getX(), corner.getY(), corner.getZ());
                    case Y -> new BlockPos(corner.getX(), contact.getY(), corner.getZ());
                    case Z -> new BlockPos(corner.getX(), corner.getY(), contact.getZ());
                };
            }
        }
        // Use the same corner as a two-wide fuselage placed against this face.
        var across = axis.isVertical() ? Direction.EAST : face.getClockWise();
        var up = axis.isVertical() ? Direction.SOUTH : Direction.UP;
        return minimum(contact, contact.relative(across.getOpposite()).relative(up.getOpposite()));
    }

    private static BlockPos minimum(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static boolean canPlace(BlockPlaceContext context, BlockPos support, List<BlockPos> cells) {
        var level = context.getLevel();
        var plot = Sable.HELPER.getContaining(level, support);
        var player = context.getPlayer();
        return cells.stream().allMatch(pos -> level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
            && Sable.HELPER.getContaining(level, pos) == plot && level.getBlockState(pos).canBeReplaced(context)
            && player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand()) && level.mayInteract(player, pos));
    }

    static void restore(List<BlockSnapshot> snapshots) {
        for (var snapshot : snapshots.reversed()) snapshot.restore(Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
    }
}
