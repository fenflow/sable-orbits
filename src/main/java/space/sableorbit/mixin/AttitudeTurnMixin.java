package space.sableorbit.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.phys.Vec3;
import space.sableorbit.Attitude;
import space.sableorbit.AttitudeClient;

@Mixin(Entity.class)
abstract class AttitudeTurnMixin {
    @Inject(method="turn", at=@At("HEAD"), cancellable=true)
    private void turnInBodyAxes(double horizontal, double vertical, CallbackInfo ci) {
        if ((Object)this instanceof LocalPlayer player && AttitudeClient.turn(player, horizontal, vertical))
            ci.cancel();
    }

    @Inject(method="getViewVector", at=@At("HEAD"), cancellable=true)
    private void aimAtRenderTime(float partial, CallbackInfoReturnable<Vec3> cir) {
        if (partial == 1 || !((Object)this instanceof LocalPlayer player)) return;
        var pose = AttitudeClient.pose(player, partial);
        if (pose != null) cir.setReturnValue(Attitude.forward(pose.view()));
    }
}
