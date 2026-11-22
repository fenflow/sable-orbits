package space.sableorbit.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import space.sableorbit.OrbitalParticles;

@Mixin(ClientLevel.class)
abstract class ParticleEmitterMixin {
    @WrapMethod(method="tickNonPassenger")
    private void fromEntity(Entity entity,Operation<Void> tick) {
        var previous=OrbitalParticles.emitter;
        OrbitalParticles.emitter=entity;
        try { tick.call(entity); } finally { OrbitalParticles.emitter=previous; }
    }
}
