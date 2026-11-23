package space.sableorbit;

import com.hbm.client.ClientEffects;
import com.hbm.client.particle.ParticleRotating;
import com.hbm.client.render.entity.RocketFlare;
import com.hbm.sound.AudioSystem;
import com.hbm.sound.AudioWrapper;
import com.mojang.blaze3d.vertex.PoseStack;
import com.ntmspace.atmosphere.AtmosphereHandler;
import com.ntmspace.orbit.Orbit;
import com.ntmspace.registry.RocketSounds;
import dev.golem.core.render.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

final class EngineEffects {
    private static final int MAX_ENGINE_LOOPS = 5;
    private static final WeakHashMap<EngineTile, AudioWrapper> loops = new WeakHashMap<>();
    private static WeakReference<ClientLevel> pressureLevel = new WeakReference<>(null);
    private static long pressureTick;
    private static boolean inAtmosphere;

    static boolean atmospheric(ClientLevel level) {
        if (pressureLevel.get() != level || pressureTick != level.getGameTime()) {
            if (pressureLevel.get() != level) pressureLevel = new WeakReference<>(level);
            pressureTick = level.getGameTime();
            inAtmosphere = !Orbit.inOrbit(level) && AtmosphereHandler.getCelestialAtmosphere(level).getPressure() > 0;
        }
        return inAtmosphere;
    }

    static void tick(EngineTile tile) {
        if (!tile.isBurning() || tile.engine() == null || !tile.isMaster()) {
            var loop = loops.remove(tile);
            if (loop != null) loop.stopSound();
            return;
        }
        var level = (ClientLevel)tile.getLevel();
        var deck = (ClientSubLevel)Sable.HELPER.getContaining(level, tile.getBlockPos());
        var center = tile.bounds().getCenter();
        var worldCenter = deck == null ? center : deck.logicalPose().transformPosition(center);
        double distance = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(worldCenter);
        var loop = loops.get(tile);
        if (distance > 250 * 250) {
            if (loop != null) {
                loop.stopSound();
                loops.remove(tile);
            }
        } else {
            if (loop == null && loops.size() >= MAX_ENGINE_LOOPS)
                loops.entrySet().removeIf(entry -> !entry.getValue().isPlaying());
            if ((loop == null && loops.size() < MAX_ENGINE_LOOPS) || (loop != null && !loop.isPlaying())) {
                var sound = EnginePlume.modelWidth(tile.engine()) > 1 ? RocketSounds.ROCKET_FLY_HEAVY : RocketSounds.ROCKET_FLY_LIGHT;
                loop = AudioSystem.getLoopedSound(sound.get(), SoundSource.BLOCKS, (float)worldCenter.x,
                    (float)worldCenter.y, (float)worldCenter.z, 1, 250, 1, 5);
                loop.setDoesRepeat(true);
                loop.startSound();
                loops.put(tile, loop);
            }
            if (loop != null) {
                loop.updatePosition((float)worldCenter.x, (float)worldCenter.y, (float)worldCenter.z);
                loop.keepAlive();
            }
        }
        if (distance > 350 * 350) return;
        var part = tile.engine();
        var bells = EnginePlume.bells(part);
        var facing = tile.nozzle().getOpposite().getRotation();
        var localAxis = Vec3.atLowerCornerOf(tile.nozzle().getNormal());
        var axis = deck == null ? localAxis : deck.logicalPose().transformNormal(localAxis);
        boolean atmosphere = atmospheric(level);
        // Soyuz emits once per outlet per tick. Share a five-outlet budget among very large clusters.
        int count = Math.min(atmosphere ? 5 : 2, bells.size());
        int first = Math.floorMod(level.getGameTime() * count, bells.size());
        for (int i = 0; i < count; i++) {
            var bell = bells.get((first + i) % bells.size());
            double radius = bell.radius() * EnginePlume.modelWidth(part);
            var offset = bell.outlet(part).add(level.random.nextGaussian() * .25 * radius, 0,
                level.random.nextGaussian() * .25 * radius);
            var local = center.add(new Vec3(facing.transform(offset.toVector3f())));
            var outlet = deck == null ? local : deck.logicalPose().transformPosition(local);
            var exhaust = axis.scale(.75 + level.random.nextDouble() * .5);
            if (atmosphere) {
                var velocity = deck == null ? exhaust : DeckFrame.velocityAt(deck, outlet).add(exhaust);
                ClientEffects.spawnRocketFlame(level, outlet.x, outlet.y, outlet.z,
                    (float)Math.clamp(radius * 1.5, .25, 1.25), velocity.x, velocity.y, velocity.z,
                    300 + level.random.nextInt(50), EnginePlume.fuel(part).flame);
            } else {
                vacuumMote(level, deck, outlet, exhaust);
            }
        }
    }

    static CompatParticle vacuumMote(ClientLevel level, ClientSubLevel deck, Vec3 outlet, Vec3 exhaust) {
        var velocity = deck == null ? exhaust : DeckFrame.velocityAt(deck, outlet).add(exhaust);
        var mote = new VacuumMote(level, outlet);
        if (Orbit.inOrbit(level)) ((OrbitalParticles.Drifting)mote).sableorbits$launch(velocity);
        else mote.setParticleSpeed(velocity.x, velocity.y, velocity.z);
        Particles.add(mote);
        return mote;
    }

    private static class VacuumMote extends CompatParticle {
        VacuumMote(ClientLevel level, Vec3 outlet) {
            super(level, outlet.x, outlet.y, outlet.z, Particles.sprite(ParticleRotating.PARTICLE_BASE));
            setLayer(ParticleLayer.EMISSIVE);
            setQuadSize(.035f + random.nextFloat() * .035f);
            hasPhysics = false;
            friction = 1;
            lifetime = 12 + random.nextInt(12);
        }
    }

    static void flares(EngineTile tile, PoseStack pose, MultiBufferSource buffers, float partialTick, float time) {
        var part = tile.engine();
        var color = EnginePlume.fuel(part).flame;
        var camera = CameraInfo.current();
        // The block renderer already includes the hull and nozzle rotations. Express the
        // camera billboard in engine axes so a rolling hull cannot roll the flare.
        var billboard = tile.nozzle().getOpposite().getRotation().conjugate();
        if (Sable.HELPER.getContaining(tile.getLevel(), tile.getBlockPos()) instanceof ClientSubLevel hull)
            billboard.mul(new Quaternionf(hull.renderPose(partialTick).orientation()).conjugate());
        var localCamera = new CameraInfo(camera.pos, camera.yRot, billboard.mul(camera.orientation));
        var sink = new RecordingSubmitSink();
        for (var bell : EnginePlume.bells(part)) {
            var offset = bell.outlet(part);
            float radius = EnginePlume.modelWidth(part) * bell.radius();
            float pulse = (float)(1 + .08 * Math.sin(time * 1.7 + 17 * bell.x() + 7 * bell.z()));
            pose.pushPose();
            pose.translate(offset.x, offset.y - .12 * radius, offset.z);
            RocketFlare.submit(pose, sink, localCamera, 3 * radius * pulse, color.r(), color.g(), color.b());
            pose.popPose();
        }
        sink.flush(buffers, tile.getLevel());
    }
}
