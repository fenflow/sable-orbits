package space.sableorbit.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.phys.Vec3;
import space.sableorbit.Attitude;
import space.sableorbit.AttitudeClient;
import space.sableorbit.BodyBox;

@Mixin(EntityRenderDispatcher.class)
abstract class AttitudeDebugMixin {
    @Shadow private static void renderVector(PoseStack stack, VertexConsumer buffer, Vector3f start, Vec3 vector, int color) {
        throw new AssertionError();
    }
    @Inject(method="renderHitbox", at=@At("HEAD"), cancellable=true)
    private static void orientedHitbox(PoseStack stack, VertexConsumer lines, Entity entity, float partial,
                                       float red, float green, float blue, CallbackInfo ci) {
        if (!(entity instanceof LivingEntity living) || !living.level().isClientSide || !(living.getBoundingBox() instanceof BodyBox)) return;
        var pose = AttitudeClient.pose(living, partial);
        if (pose == null) return;
        var bounds = living.getDimensions(living.getPose()).makeBoundingBox(0, 0, 0);
        stack.pushPose();
        pose.rotate(stack);
        LevelRenderer.renderLineBox(stack, lines, bounds, red, green, blue, 1);
        float eye = living.getEyeHeight();
        LevelRenderer.renderLineBox(stack, lines, bounds.minX, eye-.01, bounds.minZ, bounds.maxX, eye+.01, bounds.maxZ, 1, 0, 0, 1);
        stack.popPose();
        renderVector(stack, lines, pose.eyeOffset(eye).toVector3f(), Attitude.forward(pose.view()).scale(2), 0xff0000ff);
        ci.cancel();
    }
}
