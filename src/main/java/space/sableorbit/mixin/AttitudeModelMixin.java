package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.AttitudeClient;
import space.sableorbit.AttitudeClient.RenderPose;

@Mixin(LivingEntityRenderer.class)
abstract class AttitudeModelMixin {
    @Inject(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at=@At(value="INVOKE", target="Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift=At.Shift.AFTER))
    private void orientBody(LivingEntity living, float yaw, float partial, PoseStack stack, MultiBufferSource buffers,
                            int light, CallbackInfo ci, @Share("body") LocalRef<RenderPose> shared) {
        var pose = AttitudeClient.pose(living, partial);
        shared.set(pose);
        if (pose != null) pose.rotate(stack);
    }

    @ModifyExpressionValue(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at=@At(value="INVOKE", target="Lnet/minecraft/util/Mth;rotLerp(FFF)F", ordinal=0))
    private float bodyHeading(float original, @Share("body") LocalRef<RenderPose> shared) {
        return shared.get() == null ? original : 0;
    }

    @ModifyExpressionValue(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at=@At(value="INVOKE", target="Lnet/minecraft/util/Mth;rotLerp(FFF)F", ordinal=1))
    private float relativeHeadYaw(float original, @Share("body") LocalRef<RenderPose> shared) {
        return shared.get() == null ? original : shared.get().headYaw();
    }

    @ModifyExpressionValue(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at=@At(value="INVOKE", target="Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal=0))
    private float relativeHeadPitch(float original, @Share("body") LocalRef<RenderPose> shared) {
        return shared.get() == null ? original : shared.get().headPitch();
    }
}
