package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.Attitude;
import space.sableorbit.AttitudeClient;
import net.minecraft.world.phys.Vec3;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

@Mixin(PlayerRenderer.class)
abstract class EvaSwimModelMixin {
    @ModifyReturnValue(method="getRenderOffset(Lnet/minecraft/client/player/AbstractClientPlayer;F)Lnet/minecraft/world/phys/Vec3;", at=@At("RETURN"))
    private Vec3 lowerOntoDeck(Vec3 vanilla, AbstractClientPlayer player, float partial) {
        if (!player.isCrouching()) return vanilla;
        var pose = AttitudeClient.pose(player, partial);
        return pose == null ? vanilla : new Vec3(pose.body().transform(vanilla.toVector3f()));
    }

    @WrapOperation(method="setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
        at=@At(value="INVOKE", target="Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void positionSwimmingHead(PoseStack stack, float x, float y, float z, Operation<Void> original,
                                      AbstractClientPlayer player, PoseStack ignored, float bob, float yaw, float partial, float scale) {
        if (!Attitude.free(player)) {
            original.call(stack, x, y, z);
            return;
        }
        // PlayerRenderer has already pitched the swimmer around its origin. Place the
        // animated head at the swim eye in that frame, including during the prone transition.
        double amount = player.getSwimAmount(partial);
        double lean = -Math.PI * .5 * amount;
        double head = -Math.PI * .25 * amount;
        double neck = 1.501 * .9375; // LivingEntityRenderer model origin and PlayerRenderer scale
        double aboveNeck = player.getDimensions(Pose.STANDING).eyeHeight() / scale - neck;
        double eye = player.getEyeHeight() / scale;
        stack.translate(0, eye * Math.cos(lean) - neck - aboveNeck * Math.cos(head),
            -eye * Math.sin(lean) + aboveNeck * Math.sin(head));
    }
}
