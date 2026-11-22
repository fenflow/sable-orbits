package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.BodyBox;
import space.sableorbit.GravityFrame;

@Mixin(EntityRenderDispatcher.class)
abstract class DeckObjectRenderMixin {
    @Unique private static Quaternionf sableorbits$objectRotation(Entity entity, float partial) {
        if (!GravityFrame.nonliving(entity)) return null;
        var frame = GravityFrame.resolve(entity);
        if (!(frame.deck() instanceof ClientSubLevel deck)) return null;
        var normal = frame.localUp();
        return new Quaternionf(deck.renderPose(partial).orientation())
            .rotateTo(0, 1, 0, normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }

    @WrapOperation(method="render", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void renderDeckObject(EntityRenderer<?> renderer, Entity entity, float yaw, float partial,
                                  PoseStack stack, MultiBufferSource buffers, int light, Operation<Void> original) {
        var rotation = sableorbits$objectRotation(entity, partial);
        if (rotation == null) {
            original.call(renderer, entity, yaw, partial, stack, buffers, light);
            return;
        }
        stack.pushPose();
        try {
            stack.mulPose(rotation);
            original.call(renderer, entity, yaw, partial, stack, buffers, light);
        } finally {
            stack.popPose();
        }
    }

    @Inject(method="renderHitbox", at=@At("HEAD"), cancellable=true)
    private static void renderDeckObjectBounds(PoseStack stack, VertexConsumer lines, Entity entity, float partial,
                                                float red, float green, float blue, CallbackInfo ci) {
        if (!(entity.getBoundingBox() instanceof BodyBox) || !entity.level().isClientSide) return;
        var rotation = sableorbits$objectRotation(entity, partial);
        if (rotation == null) return;
        stack.pushPose();
        stack.mulPose(rotation);
        var bounds = entity.getDimensions(entity.getPose()).makeBoundingBox(0, 0, 0);
        LevelRenderer.renderLineBox(stack, lines, bounds, red, green, blue, 1);
        stack.popPose();
        ci.cancel();
    }
}
