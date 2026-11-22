package space.sableorbit.mixin;

import dev.ryanhcode.sable.ActiveSableCompanion;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.AttitudeClient;

@Mixin(value=ActiveSableCompanion.class, remap=false)
abstract class AttitudePickMixin {
    // Sable routes both block and entity pick rays through this helper.
    @Inject(method="getEyePositionInterpolated", at=@At("HEAD"), cancellable=true)
    private void eyesInBodyAxes(Entity entity, float partial, CallbackInfoReturnable<Vec3> cir) {
        if (!(entity instanceof LocalPlayer player)) return;
        var pose = AttitudeClient.cameraPose(player, partial);
        if (pose != null) cir.setReturnValue(AttitudeClient.eyes(player, pose, partial, player.getEyeHeight()));
    }
}
