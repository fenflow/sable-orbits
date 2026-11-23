package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.Attitude;

@Mixin(LocalPlayer.class)
abstract class EvaInputMixin {
    @Unique private boolean sableorbits$verticalInput;
    @Unique private boolean sableorbits$sneakInput;

    // The vanilla escape impulse samples an entire world column. A rotated corner
    // grazing the floor must not launch a swimmer horizontally out of a doorway.
    @ModifyExpressionValue(method="moveTowardsClosestSpace", at=@At(value="INVOKE",
        target="Lnet/minecraft/client/player/LocalPlayer;suffocatesAt(Lnet/minecraft/core/BlockPos;)Z", ordinal=0))
    private boolean embeddedSwimmer(boolean blocked) {
        if (!blocked) return false;
        var player = (LocalPlayer)(Object)this;
        return !Attitude.free(player) || player.level().collidesWithSuffocatingBlock(player,
            AABB.ofSize(player.getEyePosition(), .002, .002, .002));
    }

    @Inject(method="isMovingSlowly", at=@At("HEAD"), cancellable=true)
    private void freeSwimSpeed(CallbackInfoReturnable<Boolean> result) {
        if (Attitude.free((LocalPlayer)(Object)this)) result.setReturnValue(false);
    }

    @Inject(method="aiStep", at=@At(value="INVOKE",
        target="Lnet/neoforged/neoforge/client/ClientHooks;onMovementInputUpdate(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/client/player/Input;)V", shift=At.Shift.AFTER))
    private void readVerticalThrust(CallbackInfo ci) {
        var player = (LocalPlayer)(Object)this;
        sableorbits$verticalInput = Attitude.free(player);
        if (!sableorbits$verticalInput) return;
        var input = player.input;
        sableorbits$sneakInput = input.shiftKeyDown;
        player.yya = (input.jumping ? 1 : 0) - (sableorbits$sneakInput ? 1 : 0);
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    @Inject(method="aiStep", at=@At("RETURN"))
    private void finishThrust(CallbackInfo ci) {
        if (!sableorbits$verticalInput) return;
        var player = (LocalPlayer)(Object)this;
        player.yya = 0;
        // Interactions and the normal sneak packet both read this after movement.
        player.input.shiftKeyDown = sableorbits$sneakInput;
    }
}
