package space.sableorbit;

import com.hbm.client.model.WavefrontObj;
import com.mojang.blaze3d.vertex.PoseStack;
import com.ntmspace.client.render.rocket.RocketPronter;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class EngineRenderer implements BlockEntityRenderer<EngineTile> {
    private final RocketPronter models = new RocketPronter();
    @Override public AABB getRenderBoundingBox(EngineTile tile) { return tile.bounds(); }
    @Override public void render(EngineTile tile,float tick,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var part = tile.engine();
        if (part == null || !tile.isMaster()) return;
        var bounds = tile.bounds();
        var center = bounds.getCenter().subtract(Vec3.atLowerCornerOf(tile.getBlockPos()));
        pose.pushPose();
        pose.translate(center.x,center.y,center.z);
        pose.mulPose(tile.getBlockState().getValue(EngineBlock.FACING).getOpposite().getRotation());
        if (part.getModel(false) != null && part.skin() != null) {
            WavefrontObj mesh = models.mesh(part.getModel(false));
            float[] min = mesh.boundsMin(), max = mesh.boundsMax();
            float width = (float)Math.max(1,Math.max(part.topRadius(),part.bottomRadius()));
            float horizontal = width / Math.max(0.001f,Math.max(max[0]-min[0],max[2]-min[2]));
            pose.scale(horizontal,(float)part.height()/Math.max(0.001f,max[1]-min[1]),horizontal);
            pose.translate(-(min[0]+max[0])/2,-(min[1]+max[1])/2,-(min[2]+max[2])/2);
            mesh.renderAll(pose.last(),buffers.getBuffer(RenderType.entityCutout(part.skin())),light,overlay);
        } else {
            net.minecraft.client.Minecraft.getInstance().getItemRenderer().renderStatic(new net.minecraft.world.item.ItemStack(part.item()),
                net.minecraft.world.item.ItemDisplayContext.FIXED,light,overlay,pose,buffers,tile.getLevel(),0);
        }
        pose.popPose();
    }
}
