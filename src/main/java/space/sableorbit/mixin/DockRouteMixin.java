package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.orbit.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
@Mixin(value=SpaceTeleporter.class,remap=false)
abstract class DockRouteMixin {
    @Inject(method="teleport",at=@At("HEAD"),cancellable=true)
    private static void route(Entity entity,ResourceKey<Level> key,double x,double y,double z,boolean grounded,CallbackInfo ci) {
        if (Orbit.ORBIT.equals(key) && Docking.redirect(entity,x,z)) ci.cancel();
    }
}
