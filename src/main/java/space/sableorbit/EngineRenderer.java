package space.sableorbit;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.golem.core.render.CameraInfo;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class EngineRenderer implements BlockEntityRenderer<EngineTile> {
    private final RocketPartModels models = new RocketPartModels();
    @Override public AABB getRenderBoundingBox(EngineTile tile) {
        var bounds = tile.bounds();
        if (!tile.isBurning() || tile.engine() == null) return bounds;
        var direction = tile.getBlockState().getValue(EngineBlock.FACING);
        return bounds.expandTowards(Vec3.atLowerCornerOf(direction.getNormal()).scale(EnginePlume.maxLength(tile.engine())))
            .inflate(EnginePlume.modelWidth(tile.engine()) * 1.5);
    }
    @Override public int getViewDistance() { return 350; }
    @Override public void render(EngineTile tile,float tick,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var part = tile.engine();
        if (part == null || !tile.isMaster()) return;
        var visualCenter = tile.visualCenter();
        var center = visualCenter.subtract(Vec3.atLowerCornerOf(tile.getBlockPos()));
        pose.pushPose();
        pose.translate(center.x,center.y,center.z);
        pose.mulPose(tile.modelRotation());
        pose.pushPose();
        if (part.getModel(false) != null && part.skin() != null) {
            models.render(part, EnginePlume.modelWidth(part), (float)part.height(), pose, buffers, tile.getLevel(), light, overlay);
        } else {
            net.minecraft.client.Minecraft.getInstance().getItemRenderer().renderStatic(new net.minecraft.world.item.ItemStack(part.item()),
                net.minecraft.world.item.ItemDisplayContext.FIXED,light,overlay,pose,buffers,tile.getLevel(),0);
        }
        pose.popPose();
        if (tile.isBurning()) {
            var level = (ClientLevel)tile.getLevel();
            var camera = CameraInfo.current();
            var hullPose = Sable.HELPER.getContaining(level, tile.getBlockPos()) instanceof ClientSubLevel hull
                ? hull.renderPose(tick) : null;
            var worldCenter = hullPose == null ? visualCenter : hullPose.transformPosition(visualCenter);
            double distanceSquared = camera.pos.distanceToSqr(worldCenter);
            float time = (level.getGameTime() % 24000) + tick;
            if (EngineEffects.atmospheric(level)) EngineEffects.flares(tile, pose, buffers, camera, hullPose, time, distanceSquared);
            else EnginePlume.render(part, pose, buffers, time, tile.getBlockPos().hashCode() & 255, distanceSquared);
        }
        pose.popPose();
    }
}
