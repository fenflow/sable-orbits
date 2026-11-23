package space.sableorbit.mixin;

import com.ntmspace.atmosphere.AtmosphereBlob;
import com.ntmspace.orbit.Orbit;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AtmosphereBlob.class, remap = false)
abstract class DecompressionMixin {
    // Continuum x Sable handles plot breaches at the head of decompress and cancels
    // its native body. Observe the sealed-blob failure that invokes either implementation.
    @WrapOperation(method = "run", at = @At(value = "INVOKE",
        target = "Lcom/ntmspace/atmosphere/AtmosphereBlob;decompress(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)V"))
    private void recoil(AtmosphereBlob blob, BlockPos breach, Direction towardCabin, Operation<Void> decompress) {
        decompress.call(blob, breach, towardCabin);
        var provider = blob.provider();
        if (!(provider.atmosphereLevel() instanceof ServerLevel level) || !Orbit.inOrbit(level)) return;
        if (!(Sable.HELPER.getContaining(level, breach) instanceof ServerSubLevel hull)) return;
        double impulse = Math.clamp(blob.getBlobSize() * provider.getFluidPressure() * 20, 0, 2400);
        var body = RigidBodyHandle.of(hull);
        if (impulse > 0 && body != null && body.isValid())
            body.applyImpulseAtPoint(Vec3.atCenterOf(breach), Vec3.atLowerCornerOf(towardCabin.getNormal()).scale(impulse));
    }
}
