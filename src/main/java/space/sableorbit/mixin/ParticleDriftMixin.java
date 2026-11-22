package space.sableorbit.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import space.sableorbit.OrbitalParticles;

@Mixin(Particle.class)
abstract class ParticleDriftMixin implements OrbitalParticles.Drifting {
    @Shadow @Final protected ClientLevel level;
    @Shadow protected double x,y,z;
    @Shadow public abstract void setPos(double x,double y,double z);
    @Unique private Vec3 sableorbits$velocity;
    @Unique private double sableorbits$lastTick;
    @Unique private boolean sableorbits$started;

    public Vec3 sableorbits$velocity() { return sableorbits$velocity; }

    public void sableorbits$launch(Vec3 velocity) {
        sableorbits$velocity = velocity;
        sableorbits$lastTick = SubLevelContainer.getContainer(level).getInterpolation().mostRecentInterpolationTick;
    }

    public void sableorbits$beginTick() {
        if (sableorbits$velocity==null) return;
        var nativeParticle=(ParticleExtension)this;
        if (!sableorbits$started) {
            // Some providers set a tracking anchor after ParticleEngine.add returns.
            // Release it after all spawn callbacks, before Sable can apply a second kick.
            nativeParticle.sable$initialKickOut();
            nativeParticle.sable$setTrackingSubLevel(null,Vec3.ZERO);
            sableorbits$started=true;
        } else if (nativeParticle.sable$getTrackingSubLevel()!=null) {
            sableorbits$velocity=null;
        }
    }
    public void sableorbits$advance() {
        if (sableorbits$velocity==null) return;
        var clock=SubLevelContainer.getContainer(level).getInterpolation();
        // Newly queued particles can wait a tick before ParticleEngine first simulates them.
        double dt = clock.mostRecentInterpolationTick - sableorbits$lastTick;
        sableorbits$lastTick = clock.mostRecentInterpolationTick;
        var v=sableorbits$velocity;
        setPos(x+dt*v.x,y+dt*v.y,z+dt*v.z);
    }
}
