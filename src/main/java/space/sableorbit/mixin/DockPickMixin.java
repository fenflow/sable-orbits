package space.sableorbit.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
@Mixin(GameRenderer.class)
abstract class DockPickMixin {
    @Inject(method="pick(F)V",at=@At("HEAD"))
    private void alignDockedTargets(float partial,CallbackInfo ci) {
        var level=Minecraft.getInstance().level;
        BerthBox.clientRockets.removeIf(r -> r.isRemoved() || r.level()!=level);
        for(var r:BerthBox.clientRockets) {
            var pos=r.getEntityData().get(Berth.PORT);
            if(Sable.HELPER.getContaining(level,pos) instanceof ClientSubLevel ship)
                BerthBox.update(r,ship.renderPose(partial),Berth.renderGap(r,partial));
        }
    }
}
