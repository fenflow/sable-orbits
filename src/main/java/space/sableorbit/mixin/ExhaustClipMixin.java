package space.sableorbit.mixin;

import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.ExhaustClipBatch;

@Mixin(value = ActiveSableCompanion.class, remap = false)
abstract class ExhaustClipMixin {
    @Inject(method = "getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;",
        at = @At("HEAD"), cancellable = true)
    private void exhaustCandidates(Level level, BoundingBox3dc bounds, CallbackInfoReturnable<Iterable<SubLevel>> result) {
        if (!(level instanceof ServerLevel)) return;
        var candidates = ExhaustClipBatch.intersecting(level, bounds);
        if (candidates != null) result.setReturnValue(candidates);
    }
}
