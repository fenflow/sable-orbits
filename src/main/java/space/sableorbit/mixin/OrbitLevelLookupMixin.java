package space.sableorbit.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.OrbitSpaces;

@Mixin(MinecraftServer.class)
abstract class OrbitLevelLookupMixin {
    @Inject(method = "getLevel", at = @At("RETURN"), cancellable = true)
    private void reopenParkedOrbit(ResourceKey<Level> dimension, CallbackInfoReturnable<ServerLevel> result) {
        if (result.getReturnValue() != null || dimension == null) return;
        var level = OrbitSpaces.reopen((MinecraftServer)(Object)this, dimension);
        if (level != null) result.setReturnValue(level);
    }
}
