package space.sableorbit;

import dev.ryanhcode.sable.Sable;
import com.ntmspace.orbit.Orbit;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class OrbitalParticles {
    public interface Drifting {
        void sableorbits$launch(Vec3 velocity);
        Vec3 sableorbits$velocity();
        void sableorbits$beginTick();
        void sableorbits$advance();
    }
    public static Entity emitter;
    public static Drifting parent;

    public static void launch(ClientLevel level, Particle particle) {
        if (!Orbit.inOrbit(level)) return;
        var nativeParticle=(ParticleExtension)particle;
        nativeParticle.sable$initialKickOut();
        var position=particle.getPos();
        var deck=(ClientSubLevel)nativeParticle.sable$getTrackingSubLevel();
        if (particle instanceof ParticleSubLevelKickable effect && !effect.sable$shouldKickFromTracking()) return;
        Vec3 velocity=parent == null ? null : parent.sableorbits$velocity();
        var source=emitter;
        if (deck==null && velocity==null && source!=null && source.level()==level) {
            if (source.hasData(LaunchMotion.DRIFT)) velocity=source.getData(LaunchMotion.DRIFT);
            else deck=(ClientSubLevel)Sable.HELPER.getTrackingOrVehicleSubLevel(source);
        }
        var pilot=Minecraft.getInstance().player;
        if (deck==null && velocity==null && pilot!=null && (source==pilot || source==null && pilot.getBoundingBox().inflate(.5).contains(position))) {
            deck=(ClientSubLevel)Sable.HELPER.getTrackingOrVehicleSubLevel(pilot);
            if (deck==null && DeckFrame.departureHull(pilot)!=null) velocity=DeckFrame.freeVelocity(pilot);
        }
        if (deck==null && source==null && velocity==null)
            deck=(ClientSubLevel)LaunchMotion.nearbyHull(level,position);
        if (deck!=null) velocity=DeckFrame.velocityAt(deck,position);
        if (nativeParticle.sable$getTrackingSubLevel()!=null) nativeParticle.sable$setTrackingSubLevel(null,Vec3.ZERO);
        if (velocity!=null) ((Drifting)particle).sableorbits$launch(velocity);
    }
}
