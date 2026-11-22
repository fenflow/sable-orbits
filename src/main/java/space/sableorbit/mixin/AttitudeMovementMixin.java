package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.Attitude;
import space.sableorbit.GravityFrame;

@Mixin(Entity.class)
abstract class AttitudeMovementMixin {
    @WrapOperation(method="moveRelative", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 steer(Vec3 input, float speed, float yaw, Operation<Vec3> vanilla) {
        if ((Object)this instanceof Mob mob) {
            var impulse = vanilla.call(input, speed, yaw);
            if (impulse == Vec3.ZERO) return impulse;
            var frame = GravityFrame.resolve(mob);
            return frame.deck() == null ? impulse : new Vec3(Attitude.deckBasis(frame).transform(impulse.toVector3f()));
        }
        if (!((Object)this instanceof Player player) || input.lengthSqr() < 1.0e-7)
            return vanilla.call(input, speed, yaw);
        var frame = GravityFrame.resolve(player);
        if (!frame.free() && frame.deck() == null) return vanilla.call(input, speed, yaw);
        var orientation = frame.free() ? Attitude.freeLook(player) : Attitude.deckBody(player, frame.up(), yaw);
        var scaled = (input.lengthSqr() > 1 ? input.normalize() : input).scale(speed);
        return new Vec3(orientation.transform(scaled.toVector3f()));
    }
}
