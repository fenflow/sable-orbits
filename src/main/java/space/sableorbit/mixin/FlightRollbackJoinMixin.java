package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.FlightRollback;

@Mixin(ServerLevel.class)
abstract class FlightRollbackJoinMixin {
    @ModifyExpressionValue(method = "addPlayer", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/event/entity/EntityJoinLevelEvent;isCanceled()Z"))
    private boolean allowRejoin(boolean canceled, ServerPlayer player) {
        return canceled && !FlightRollback.rejoining(player, (ServerLevel) (Object) this);
    }
}
