package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import space.sableorbit.LaunchMotion;

@Mixin(ServerLevel.class)
abstract class LaunchMixin {
    // Run before Sable's HEAD injection projects a plot-space outlet into the world.
    @WrapMethod(method="addFreshEntity")
    private boolean inheritLaunchMotion(Entity entity, Operation<Boolean> spawn) {
        LaunchMotion.launch(entity);
        return spawn.call(entity);
    }
}
