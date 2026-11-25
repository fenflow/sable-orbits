package space.sableorbit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class ReactionWheelRenderer implements BlockEntityRenderer<ReactionWheelTile> {
    private final BlockRenderDispatcher blocks;

    ReactionWheelRenderer(BlockRenderDispatcher blocks) {
        this.blocks = blocks;
    }

    @Override public AABB getRenderBoundingBox(ReactionWheelTile tile) {
        var pos = tile.getBlockPos();
        return tile.block().wide() && tile.isMaster()
            ? new AABB(pos).minmax(new AABB(pos.offset(ReactionWheelBlock.sectionOffset(tile.getBlockState(), 3))))
            : new AABB(pos);
    }

    @Override public int getViewDistance() { return 350; }

    @Override public void render(ReactionWheelTile tile, float partialTick, PoseStack pose, MultiBufferSource buffers,
                                 int light, int overlay) {
        if (!tile.block().wide() || !tile.isMaster()) return;
        var center = getRenderBoundingBox(tile).getCenter().subtract(Vec3.atLowerCornerOf(tile.getBlockPos()));
        var state = tile.getBlockState();
        pose.pushPose();
        pose.translate(center.x, center.y, center.z);
        pose.mulPose(Axis.YP.rotationDegrees(-90 * state.getValue(ReactionWheelBlock.FOOTPRINT_ROTATION).ordinal()));
        pose.mulPose(Direction.get(Direction.AxisDirection.POSITIVE, ReactionWheelBlock.originalAxis(state)).getRotation());
        pose.translate(-0.5, -0.5, -0.5);
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), state,
            blocks.getBlockModel(state), 1, 1, 1, light, overlay);
        pose.popPose();
    }
}
