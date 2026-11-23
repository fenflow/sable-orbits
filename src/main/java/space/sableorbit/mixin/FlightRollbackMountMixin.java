package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.FlightRollback;

@Mixin(Entity.class)
abstract class FlightRollbackMountMixin {
    @WrapOperation(method = {"startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", "removeVehicle"},
        at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;canMountEntity(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Z)Z"))
    private boolean allowRestore(Entity rider, Entity mount, boolean mounting, Operation<Boolean> original) {
        return original.call(rider, mount, mounting) || FlightRollback.restoringRide(rider, mount, mounting);
    }
}
