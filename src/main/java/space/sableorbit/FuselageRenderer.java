package space.sableorbit;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class FuselageRenderer implements BlockEntityRenderer<FuselageTile> {
    private final RocketPartModels models = new RocketPartModels();

    @Override public AABB getRenderBoundingBox(FuselageTile tile) {
        var part = tile.part();
        var pos = tile.getBlockPos();
        if (part == null || !tile.isMaster()) return new AABB(pos);
        var cells = tile.cells();
        return new AABB(cells.getFirst()).minmax(new AABB(cells.getLast()));
    }

    @Override public int getViewDistance() {
        return 350;
    }

    @Override public void render(FuselageTile tile, float tick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var part = tile.part();
        if (part == null || !tile.isMaster() || part.getModel(false) == null || part.skin() == null) return;

        var center = getRenderBoundingBox(tile).getCenter().subtract(Vec3.atLowerCornerOf(tile.getBlockPos()));
        float width = (float) Math.max(part.topRadius(), part.bottomRadius());
        float height = (float)Math.ceil(part.height());

        pose.pushPose();
        pose.translate(center.x, center.y, center.z);
        pose.mulPose(tile.getBlockState().getValue(FuselageBlock.FACING).getRotation());
        models.render(part, width, height, pose, buffers, tile.getLevel(), light, overlay);
        pose.popPose();
    }
}
