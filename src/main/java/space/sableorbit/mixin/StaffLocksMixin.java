package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;


@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
abstract class StaffLocksMixin {
    // Dynamic station dimensions can be visited before Simulated sends the first lock list.
    @ModifyExpressionValue(method = {"isLocked", "getLocks"}, at = @At(value = "INVOKE",
        target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object sableorbits$locksForDimension(Object locks) {
        return locks == null ? List.of() : locks;
    }
}
