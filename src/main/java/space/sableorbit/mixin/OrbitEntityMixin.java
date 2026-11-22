package space.sableorbit.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.OrbitSpaces;

@Mixin(Entity.class)
abstract class OrbitEntityMixin {
    @Inject(method="checkBelowWorld",at=@At("HEAD"),cancellable=true)
    private void ignoreOrbitFloor(CallbackInfo ci) {
        if(OrbitSpaces.isolated(((Entity)(Object)this).level())) ci.cancel();
    }
    @Inject(method="applyGravity",at=@At("HEAD"),cancellable=true)
    private void weightlessItem(CallbackInfo ci) {
        if((Object)this instanceof ItemEntity item && OrbitSpaces.isolated(item.level())) ci.cancel();
    }
}
