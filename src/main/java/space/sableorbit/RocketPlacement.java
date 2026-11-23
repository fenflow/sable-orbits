package space.sableorbit;

import com.hbm.items.weapon.ItemCustomMissilePart.PartType;
import com.ntmspace.rocket.RocketPart;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.joml.Vector3d;
import java.util.List;

record RocketPlacement(BlockPos origin, BlockPos contact, Direction facing, List<BlockPos> cells, boolean placeable) {
    static RocketPlacement of(RocketPart part, UseOnContext use) {
        var context = new BlockPlaceContext(use);
        var player = use.getPlayer();
        var level = use.getLevel();
        var plot = Sable.HELPER.getContaining(level, use.getClickedPos());
        Direction face = use.getClickedFace();
        Direction facing = face;
        BlockPos contact = context.getClickedPos();
        BlockPos origin = contact;
        if (part.type() == PartType.THRUSTER) {
            var view = player.getLookAngle();
            var local = new Vector3d(view.x, view.y, view.z);
            if (plot != null) plot.logicalPose().orientation().transformInverse(local);
            facing = Direction.getNearest(local.x, local.y, local.z);
            if (player.isShiftKeyDown()) facing = facing.getOpposite();
            origin = Engines.inletAtFace(part, origin, face, facing);
        }
        var cells = Engines.cells(part, origin, facing);
        boolean valid = cells.stream().allMatch(pos -> level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
            && Sable.HELPER.getContaining(level, pos) == plot && level.getBlockState(pos).canBeReplaced(context)
            && player.mayUseItemAt(pos, face, use.getItemInHand()) && level.mayInteract(player, pos));
        return new RocketPlacement(origin, contact, facing, cells, valid);
    }

    static void restore(List<BlockSnapshot> snapshots) {
        for (var snapshot : snapshots.reversed()) snapshot.restore(Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
    }
}
