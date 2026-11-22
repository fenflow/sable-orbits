package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ntmspace.orbit.Orbit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.LaunchMotion;
import space.sableorbit.GravityFrame;
import space.sableorbit.OrbitSpaces;

@Mixin(Entity.class)
abstract class OrbitEntityMixin {
    @Inject(method="checkBelowWorld",at=@At("HEAD"),cancellable=true)
    private void ignoreOrbitFloor(CallbackInfo ci) {
        if(OrbitSpaces.isolated(((Entity)(Object)this).level())) ci.cancel();
    }
    @ModifyReturnValue(method="getGravity",at=@At("RETURN"))
    private double localGravity(double base) {
        var e=(Entity)(Object)this;
        if (base != 0 && GravityFrame.nonliving(e) && OrbitSpaces.isolated(e.level())) {
            float gravity = GravityFrame.orbitalGravity(e);
            if (gravity > 0) return base * gravity / com.ntmspace.orbit.OrbitEnvironment.STANDARD_GRAVITY;
        }
        return base != 0 && LaunchMotion.ballistic(e) ? base * LaunchMotion.gravity(e) : base;
    }

    @Inject(method="load",at=@At("RETURN"))
    private void restoreFastLaunch(CompoundTag tag, CallbackInfo ci) {
        var entity = (Entity)(Object)this;
        if (!entity.hasData(LaunchMotion.DRIFT) || !Orbit.inOrbit(entity.level())) return;
        var saved = tag.getList("Motion", Tag.TAG_DOUBLE);
        if (saved.size() != 3) return;
        var carrier = entity.getData(LaunchMotion.DRIFT);
        var loaded = entity.getDeltaMovement();
        entity.setDeltaMovement(restoreComponent(loaded.x, saved.getDouble(0), carrier.x),
            restoreComponent(loaded.y, saved.getDouble(1), carrier.y),
            restoreComponent(loaded.z, saved.getDouble(2), carrier.z));
    }

    @org.spongepowered.asm.mixin.Unique
    private static double restoreComponent(double loaded, double saved, double carrier) {
        // Entity.load resets components above ten. Allow the same margin in the launch frame.
        return loaded == 0 && Math.abs(saved) > 10 && Math.abs(saved) <= Math.abs(carrier) + 10 ? saved : loaded;
    }
}
