package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.GravityFrame;

@Mixin(Boat.class)
abstract class DeckBoatMixin {
    @Shadow private Boat.Status status;

    // Boats integrate dry gravity themselves instead of calling Entity.applyGravity.
    @WrapOperation(method="floatBoat", at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/vehicle/Boat;setDeltaMovement(DDD)V", ordinal=0))
    private void dryCabinGravity(Boat boat, double x, double y, double z, Operation<Void> original) {
        if (status == Boat.Status.ON_LAND || status == Boat.Status.IN_AIR) {
            var frame = GravityFrame.resolve(boat);
            if (frame.artificial()) {
                double gravity = boat.getGravity();
                var up = frame.up();
                original.call(boat, x - up.x * gravity, y + (1 - up.y) * gravity, z - up.z * gravity);
                return;
            }
        }
        original.call(boat, x, y, z);
    }
}
