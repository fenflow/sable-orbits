package space.sableorbit.mixin;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.AttitudeClient;

@Mixin(value=Camera.class, priority=1100)
abstract class AttitudeCameraMixin {
    @Shadow private float eyeHeightOld;
    @Shadow private float eyeHeight;
    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method="setup", at=@At(value="INVOKE", target="Lnet/minecraft/client/Camera;setPosition(DDD)V", shift=At.Shift.AFTER))
    private void placeEyes(BlockGetter level, Entity entity, boolean detached, boolean reverse, float partial, CallbackInfo ci) {
        if (!(entity instanceof LivingEntity living)) return;
        var pose = AttitudeClient.cameraPose(living, partial);
        if (pose == null) return;
        // Use the same smoothed height as vanilla before the detached-camera zoom ray.
        setPosition(AttitudeClient.eyes(living, pose, partial, Mth.lerp(partial, eyeHeightOld, eyeHeight)));
    }
}
