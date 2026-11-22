package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import space.sableorbit.Attitude;

@Mixin(LivingEntity.class)
abstract class SeatDismountMixin {
    @WrapMethod(method="stopRiding")
    private void inheritSeatAxes(Operation<Void> dismount) {
        if ((Object)this instanceof Player player && (!player.level().isClientSide || player.isLocalPlayer())) {
            var view = Attitude.seatedLook(player);
            dismount.call();
            Attitude.leaveSeat(player, view);
        } else dismount.call();
    }
}
