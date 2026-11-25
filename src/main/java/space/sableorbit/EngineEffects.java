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
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Quaternionf;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = "sableorbits", value = Dist.CLIENT)
final class EngineEffects {
    private static final int MAX_ENGINE_LOOPS = 5;
    private static final WeakHashMap<EngineTile, AudioWrapper> loops = new WeakHashMap<>();
    private record Emission(EngineTile engine, ClientSubLevel deck, Vec3 center, int interval) {}
    private static final ArrayList<Emission> emissions = new ArrayList<>();
    private static int nextEmitter;
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
        var center = tile.visualCenter();
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
        if (distance > 96 * 96) return;
        int interval = distance <= 48 * 48 ? 1 : 3;
        if (Math.floorMod(level.getGameTime() + tile.getBlockPos().hashCode(), interval) == 0)
            emissions.add(new Emission(tile, deck, center, interval));
    }

    @SubscribeEvent static void emitExhaust(ClientTickEvent.Post event) {
        if (emissions.isEmpty()) return;
        var client = Minecraft.getInstance();
        var level = client.level;
        if (level != null) {
            var quality = client.options.particles().get();
            int budget = switch (quality) {
                case ALL -> 24;
                case DECREASED -> 12;
                case MINIMAL -> 4;
            };
            int perEngine = switch (quality) {
                case ALL -> 5;
                case DECREASED -> 2;
                case MINIMAL -> 1;
            };
            boolean atmosphere = atmospheric(level);
            int size = emissions.size();
            int index = Math.floorMod(nextEmitter, size);
            // Resume where last tick's budget ran out so later engines still get a turn.
            for (int visited = 0; visited < size && budget > 0; visited++) {
                var emission = emissions.get(index);
                if (emission.engine.getLevel() == level && !emission.engine.isRemoved())
                    budget -= emit(level, emission, atmosphere, perEngine, budget);
                index = (index + 1) % size;
            }
            nextEmitter = index;
        }
        emissions.clear();
    }

    private static int emit(ClientLevel level, Emission emission, boolean atmosphere, int perEngine, int budget) {
        var tile = emission.engine;
        var deck = emission.deck;
        var part = tile.engine();
        var bells = EnginePlume.bells(part);
        var facing = tile.modelRotation();
        var localAxis = Vec3.atLowerCornerOf(tile.nozzle().getNormal());
        var axis = deck == null ? localAxis : deck.logicalPose().transformNormal(localAxis);
        int outlets = Math.min(atmosphere ? perEngine : Math.min(perEngine, 2), bells.size());
        int count = Math.min(outlets, budget);
        int first = Math.floorMod(level.getGameTime() / emission.interval * outlets + tile.getBlockPos().hashCode(), bells.size());
        for (int i = 0; i < count; i++) {
            var bell = bells.get((first + i) % bells.size());
            double radius = bell.radius() * EnginePlume.modelWidth(part);
            var offset = bell.outlet(part).add(level.random.nextGaussian() * .25 * radius, 0,
                level.random.nextGaussian() * .25 * radius);
            var local = emission.center.add(new Vec3(facing.transform(offset.toVector3f())));
            var outlet = deck == null ? local : deck.logicalPose().transformPosition(local);
            var exhaust = axis.scale(.75 + level.random.nextDouble() * .5);
            if (atmosphere) {
                var velocity = deck == null ? exhaust : DeckFrame.velocityAt(deck, outlet).add(exhaust);
                ClientEffects.spawnRocketFlame(level, outlet.x, outlet.y, outlet.z,
                    (float)Math.clamp(radius * 1.5, .25, 1.25), velocity.x, velocity.y, velocity.z,
                    64 + level.random.nextInt(16), EnginePlume.fuel(part).flame);
            } else {
                vacuumMote(level, deck, outlet, exhaust);
            }
        }
        return count;
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

    static void flares(EngineTile tile, PoseStack pose, MultiBufferSource buffers, CameraInfo camera, Pose3dc hullPose,
                       float time, double distanceSquared) {
        var part = tile.engine();
        var color = EnginePlume.fuel(part).flame;
        // The block renderer already includes the hull and nozzle rotations. Express the
        // camera billboard in engine axes so a rolling hull cannot roll the flare.
        var billboard = tile.modelRotation().conjugate();
        if (hullPose != null) billboard.mul(new Quaternionf(hullPose.orientation()).conjugate());
        var localCamera = new CameraInfo(camera.pos, camera.yRot, billboard.mul(camera.orientation));
        var sink = new RecordingSubmitSink();
        var bells = EnginePlume.bells(part);
        int count = EnginePlume.visibleBells(bells, distanceSquared);
        for (int i = 0; i < count; i++) {
            var bell = bells.get(i * bells.size() / count);
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
