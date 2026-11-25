package space.sableorbit.mixin;

import com.ntmspace.handler.ReentryFlames;
import com.ntmspace.orbit.Orbit;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ReentryFlames.class, remap = false)
abstract class ReentryFlamesMixin {
    @Inject(method = "playerTick", at = @At("HEAD"), cancellable = true)
    private static void skipOrbit(Player player, CallbackInfo ci) {
        // NTM's celestial atmosphere lookup falls back to Kerbin for a station dimension.
        if (Orbit.inOrbit(player.level())) ci.cancel();
    }
}
