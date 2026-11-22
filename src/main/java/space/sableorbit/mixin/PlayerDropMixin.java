package space.sableorbit.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.LaunchMotion;

@Mixin(Player.class)
abstract class PlayerDropMixin {
    @Inject(method="drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",at=@At("RETURN"))
    private void dropped(ItemStack stack,boolean scatter,boolean includeName,CallbackInfoReturnable<ItemEntity> ci) {
        var item=ci.getReturnValue();
        if (item==null || includeName || item.level().isClientSide || item.hasData(LaunchMotion.DRIFT)) return;
        var drift=LaunchMotion.carrier((Player)(Object)this,item.position());
        if (drift.lengthSqr()>0) {
            item.setDeltaMovement(item.getDeltaMovement().add(drift));
            item.setData(LaunchMotion.DRIFT,drift);
        }
    }
}
