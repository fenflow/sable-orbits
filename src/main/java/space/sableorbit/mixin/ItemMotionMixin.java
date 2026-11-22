package space.sableorbit.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import space.sableorbit.BodyBox;
import space.sableorbit.LaunchMotion;

@Mixin(ItemEntity.class)
abstract class ItemMotionMixin {
    @WrapOperation(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;", ordinal=0))
    private Vec3 orbitalDrag(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        var item = (ItemEntity)(Object)this;
        if (item.getBoundingBox() instanceof BodyBox box && item.onGround()) {
            // Ground friction is tangential to the surface. Block-specific kick or
            // rebound velocity along the normal has its own drag coefficient.
            var up = box.up();
            return velocity.scale(x).add(up.scale(velocity.dot(up) * (y - x)));
        }
        return !item.onGround() && LaunchMotion.vacuumDrag(item, 0) == 1 ? velocity : original.call(velocity, x, y, z);
    }

    @ModifyExpressionValue(method="tick", at=@At(value="FIELD", target="Lnet/minecraft/world/phys/Vec3;y:D"))
    private double bounceAfterLanding(double worldY) {
        // BodyGeometry resolves this contact against the deck. Don't reflect a
        // tangential velocity just because the deck points down in world space.
        return ((ItemEntity)(Object)this).getBoundingBox() instanceof BodyBox ? 0 : worldY;
    }
}
