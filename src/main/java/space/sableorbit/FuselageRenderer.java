package space.sableorbit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ntmspace.client.render.rocket.RocketPronter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class FuselageRenderer implements BlockEntityRenderer<FuselageTile> {
    private final RocketPronter models = new RocketPronter();

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

        var mesh = models.mesh(part.getModel(false));
        float[] min = mesh.boundsMin();
        float[] max = mesh.boundsMax();
        var center = getRenderBoundingBox(tile).getCenter().subtract(Vec3.atLowerCornerOf(tile.getBlockPos()));
        float width = (float) Math.max(part.topRadius(), part.bottomRadius());
        float horizontal = width / Math.max(0.001f, Math.max(max[0] - min[0], max[2] - min[2]));
        float height = (float) Math.ceil(part.height()) / Math.max(0.001f, max[1] - min[1]);

        pose.pushPose();
        pose.translate(center.x, center.y, center.z);
        pose.mulPose(tile.getBlockState().getValue(FuselageBlock.FACING).getRotation());
        pose.scale(horizontal, height, horizontal);
        pose.translate(-(min[0] + max[0]) / 2, -(min[1] + max[1]) / 2, -(min[2] + max[2]) / 2);
        mesh.renderAll(pose.last(), buffers.getBuffer(RenderType.entityCutout(part.skin())), light, overlay);
        pose.popPose();
    }
}
