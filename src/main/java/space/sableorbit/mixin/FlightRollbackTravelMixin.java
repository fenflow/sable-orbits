package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.FlightRollback;

@Mixin(ServerPlayer.class)
abstract class FlightRollbackTravelMixin {
    @ModifyExpressionValue(method = "changeDimension", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/common/CommonHooks;onTravelToDimension(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceKey;)Z"))
    private boolean allowReturn(boolean allowed, DimensionTransition transition) {
        return allowed || FlightRollback.returningTo((ServerPlayer) (Object) this, transition.newLevel().dimension());
    }
}
