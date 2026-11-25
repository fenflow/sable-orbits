package space.sableorbit.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.BodyBox;

@Mixin(Snowball.class)
abstract class SnowballOriginMixin {
    @Inject(method="<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)V", at=@At("RETURN"))
    private void useTiltedEye(Level level, LivingEntity owner, CallbackInfo ci) {
        if (owner instanceof Player && owner.getBoundingBox() instanceof BodyBox)
            ((Snowball)(Object)this).setPos(owner.getEyePosition());
    }
}
