package space.sableorbit.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.OrbitalParticles;

@Mixin(value=ParticleEngine.class,priority=900)
abstract class ParticleMotionMixin {
    @Shadow protected ClientLevel level;
    @Inject(method="add(Lnet/minecraft/client/particle/Particle;)V",at=@At("RETURN"))
    private void launch(Particle p,CallbackInfo ci) { OrbitalParticles.launch(level,p); }
    @WrapMethod(method="tickParticle")
    private void tickInLaunchFrame(Particle particle, Operation<Void> tick) {
        var drift = (OrbitalParticles.Drifting)particle;
        if (drift.sableorbits$velocity() == null) {
            tick.call(particle);
            return;
        }
        var previous = OrbitalParticles.parent;
        drift.sableorbits$beginTick();
        OrbitalParticles.parent = drift;
        try {
            tick.call(particle);
            drift.sableorbits$advance();
        } finally {
            OrbitalParticles.parent = previous;
        }
    }
}
