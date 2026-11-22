package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.OrbitSpaces;

@Mixin(value=SubLevelPhysicsSystem.class,remap=false)
abstract class EmptyOrbitMixin {
    @Shadow @Final private ServerLevel level;
    @ModifyExpressionValue(method="tick",at=@At(value="FIELD",target="Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;paused:Z"))
    private boolean holdEmptyDimension(boolean paused) {
        return paused || OrbitSpaces.unoccupied(level);
    }
}
