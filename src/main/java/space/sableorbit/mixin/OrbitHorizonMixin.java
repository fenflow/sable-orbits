package space.sableorbit.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.OrbitSpaces;

@Mixin(ClientLevel.ClientLevelData.class)
abstract class OrbitHorizonMixin {
    @Inject(method="getHorizonHeight",at=@At("HEAD"),cancellable=true)
    private void noGroundHorizon(LevelHeightAccessor world, CallbackInfoReturnable<Double> result) {
        if(world instanceof ClientLevel level && OrbitSpaces.isolated(level)) result.setReturnValue(-Double.MAX_VALUE);
    }
}
