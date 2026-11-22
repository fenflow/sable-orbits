package space.sableorbit.mixin;

import com.ntmspace.orbit.Orbit;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
abstract class OrbitCloudMixin {
    @Inject(method = "getCloudsType", at = @At("HEAD"), cancellable = true)
    private void sableorbits$orbitalClouds(CallbackInfoReturnable<CloudStatus> result) {
        var level = Minecraft.getInstance().level;
        if (level != null && Orbit.inOrbit(level)) result.setReturnValue(CloudStatus.OFF);
    }
}
