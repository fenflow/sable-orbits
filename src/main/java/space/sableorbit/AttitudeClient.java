package space.sableorbit;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.WeakHashMap;

@EventBusSubscriber(modid="sableorbits", value=Dist.CLIENT)
public final class AttitudeClient {
    private static final float RAD = (float)(Math.PI / 180);
    @EventBusSubscriber(modid="sableorbits", value=Dist.CLIENT)
    public static final class Keys {
        static final KeyMapping LEFT = rollKey("left", GLFW.GLFW_KEY_Z);
        static final KeyMapping RIGHT = rollKey("right", GLFW.GLFW_KEY_C);

        private static KeyMapping rollKey(String side, int key) {
            return new KeyMapping("key.sableorbits.roll_" + side, KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, key, KeyMapping.CATEGORY_MOVEMENT);
        }
        @SubscribeEvent static void register(RegisterKeyMappingsEvent event) {
            event.register(LEFT);
            event.register(RIGHT);
        }
    }

    // Keep deck yaw and pitch in the plot. Writing world angles back to the player lets
    // picking, projectiles, and vanilla look packets follow the same direction.
    private static LocalPlayer pilot;
    private static ClientSubLevel deck;
    private static Direction floor;
    private static float localYaw, localPitch, writtenYaw, writtenPitch, rollStep;
    private static boolean free;
    private static final WeakHashMap<Player, RemoteLook> remote = new WeakHashMap<>();

    private static final class RemoteLook {
        Quaternionf from, target;
        float start;
        RemoteLook(Quaternionf first) { from = new Quaternionf(first); target = first; }
        Quaternionf sample(Quaternionf next, float time) {
            if (Math.abs(next.dot(target)) < .9999999f) {
                from = at(time);
                target = next;
                start = time;
            }
            return at(time);
        }
        private Quaternionf at(float time) {
            return new Quaternionf(from).slerp(target, Mth.clamp(time - start, 0, 1));
        }
    }

    public static boolean turn(LocalPlayer player, double horizontal, double vertical) {
        return update(player, (float)horizontal * .15f, (float)vertical * .15f, 0, false);
    }

    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearPlayer();
    }

    @SubscribeEvent static void tick(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            clearPlayer();
            return;
        }
        if (mc.isPaused()) return;
        float roll = mc.screen == null
            ? 4.5f * ((Keys.RIGHT.isDown() ? 1 : 0) - (Keys.LEFT.isDown() ? 1 : 0)) : 0;
        update(mc.player, 0, 0, roll, true);
    }

    private static void clearPlayer() {
        pilot = null;
        deck = null;
        floor = null;
        free = false;
        rollStep = 0;
        remote.clear();
        resetCamera(null);
    }

    private static boolean update(LocalPlayer player, float yaw, float pitch, float roll, boolean tick) {
        if (pilot != player) {
            deck = null;
            free = false;
            pilot = player;
            rollStep = 0;
        }
        var frame = GravityFrame.resolve(player);
        if (frame.free()) {
            Quaternionf view = Attitude.freeLook(player);
            boolean changed = yaw != 0 || pitch != 0 || roll != 0;
            if (deck != null && !deck.isRemoved() && deck.getLevel() == player.level()) {
                view = deckBasis(deck, floor, 1).mul(Attitude.look(localYaw, localPitch));
                changed = true;
            } else if (free && (player.getYRot() != writtenYaw || player.getXRot() != writtenPitch)) {
                // A teleport or another controller can change vanilla aim between mouse events.
                Vec3 desired = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
                Vec3 forward = Attitude.forward(view);
                if (forward.distanceToSqr(desired) > 1e-8) {
                    var correction = new Quaternionf().rotationTo(forward.toVector3f(), desired.toVector3f());
                    view.premul(correction).normalize();
                    changed = true;
                }
            }
            deck = null;
            free = true;
            view.rotateY(-yaw * RAD).rotateX(pitch * RAD).rotateZ(roll * RAD).normalize();
            if (tick) rollStep = roll;
            writeWorldAngles(player, view);
            // The first tracked snapshot can arrive after the first client tick in a
            // restored orbit. Only actual steering or a deck release starts prediction.
            if (changed) Attitude.setLocal(player, view);
            if (tick) Attitude.sendLocal(player);
            return true;
        }
        rollStep = 0;
        free = false;
        if (!(frame.deck() instanceof ClientSubLevel current) || player.isPassenger() || player.isSleeping()) {
            deck = null;
            return false;
        }
        var basis = deckBasis(current, frame.localUp(), 1);
        if (deck != current || floor != frame.localUp() || player.getYRot() != writtenYaw || player.getXRot() != writtenPitch) {
            Vec3 forward = player.getLookAngle();
            Vec3 local = new Vec3(basis.transformInverse(forward.toVector3f()));
            localYaw = Attitude.yaw(local);
            localPitch = Attitude.pitch(local);
            deck = current;
            floor = frame.localUp();
        }
        localYaw = Mth.wrapDegrees(localYaw + yaw);
        localPitch = Mth.clamp(localPitch + pitch, -89.9f, 89.9f);
        writeWorldAngles(player, basis.mul(Attitude.look(localYaw, localPitch)));
        return true;
    }

    private static void writeWorldAngles(LocalPlayer player, Quaternionf view) {
        Vec3 direction = Attitude.forward(view);
        float yaw = direction.horizontalDistanceSqr() < 1e-12 ? player.getYRot() : Attitude.yaw(direction);
        float pitch = Attitude.pitch(direction);
        player.yRotO += Mth.wrapDegrees(yaw - player.getYRot());
        player.xRotO += pitch - player.getXRot();
        player.setYRot(yaw);
        player.setXRot(pitch);
        writtenYaw = yaw;
        writtenPitch = pitch;
    }

    private static Quaternionf deckBasis(ClientSubLevel deck, Direction normal, float partial) {
        var plot = new Quaternionf(partial == 1 ? deck.logicalPose().orientation() : deck.renderPose(partial).orientation());
        return plot.rotateTo(0, 1, 0, normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }

    private static Quaternionf freeDisplay(Player player, float partial) {
        var view = Attitude.freeLook(player);
        if (player == pilot) return view.rotateZ(-rollStep * (1 - partial) * RAD);
        return remote.computeIfAbsent(player, p -> new RemoteLook(view)).sample(view, player.tickCount + partial);
    }

    public record RenderPose(Quaternionf body, Quaternionf view, float headYaw, float headPitch, float pivot) {
        public void rotate(com.mojang.blaze3d.vertex.PoseStack stack) {
            stack.translate(0, pivot, 0);
            stack.mulPose(body);
            stack.translate(0, -pivot, 0);
        }

        public Vec3 eyeOffset(float height) {
            return Attitude.up(body).scale(height - pivot).add(0, pivot, 0);
        }
    }

    public static Vec3 eyes(LivingEntity living, RenderPose pose, float partial, float height) {
        Vec3 feet = living.getPosition(partial);
        if (dev.ryanhcode.sable.Sable.HELPER.getTrackingSubLevel(living) instanceof ClientSubLevel plot) {
            Vec3 start = plot.lastPose().transformPositionInverse(new Vec3(living.xo, living.yo, living.zo));
            Vec3 end = plot.logicalPose().transformPositionInverse(living.position());
            feet = plot.renderPose(partial).transformPosition(start.lerp(end, partial));
        }
        if (pose.pivot() > 0)
            return feet.add(0, living.hasPose(net.minecraft.world.entity.Pose.SWIMMING) ? pose.pivot() : height, 0);
        return feet.add(pose.eyeOffset(height));
    }

    public static RenderPose pose(LivingEntity living, float partial) {
        if (living.isPassenger() || living.isSleeping()) return null;
        var frame = GravityFrame.resolve(living);
        if (living instanceof Player player && frame.free()) {
            Quaternionf view = freeDisplay(player, partial);
            return new RenderPose(view, view, 0, 0, player.getEyeHeight());
        }
        if (!(frame.deck() instanceof ClientSubLevel plot)) return null;
        float yaw = Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot);
        float headYaw = Mth.rotLerp(partial, living.yHeadRotO, living.yHeadRot);
        float pitch = Mth.lerp(partial, living.xRotO, living.getXRot());
        Quaternionf renderBasis = deckBasis(plot, frame.localUp(), partial);
        if (!(living instanceof Player)) {
            var body = new Quaternionf(renderBasis).rotateY(-yaw * RAD);
            return new RenderPose(body, renderBasis.mul(Attitude.look(headYaw, pitch)), Mth.wrapDegrees(headYaw-yaw), pitch, 0);
        }
        var delta = renderBasis.mul(deckBasis(plot, frame.localUp(), 1).conjugate(), new Quaternionf());
        float worldYaw = living == pilot ? living.getYRot() : Mth.rotLerp(partial, living.yRotO, living.getYRot());
        var body = delta.mul(Attitude.deckBody(living, frame.up(), worldYaw), new Quaternionf());
        Vec3 aim = living == pilot && plot == deck
            ? Attitude.forward(renderBasis.mul(Attitude.look(localYaw, localPitch)))
            : new Vec3(delta.transform(Vec3.directionFromRotation(pitch, headYaw).toVector3f()));
        Vec3 local = new Vec3(body.transformInverse(aim.toVector3f()));
        float relativeYaw = Attitude.yaw(local), relativePitch = Attitude.pitch(local);
        var view = new Quaternionf(body).mul(Attitude.look(relativeYaw, relativePitch));
        return new RenderPose(body, view, relativeYaw, relativePitch, 0);
    }

    private static LocalPlayer cameraPlayer;
    private static ClientSubLevel cameraFloor;
    private static Direction cameraFace;
    private static float blendStart, fromRoll, shownRoll;
    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);
    private static Vec3 fromUp = WORLD_UP, shownUp = WORLD_UP;

    private static float roll(Quaternionf view, float yaw, float pitch) {
        var twist = Attitude.look(yaw, pitch).conjugate().mul(view);
        return Mth.wrapDegrees((float)Math.toDegrees(2 * Math.atan2(twist.z, twist.w)));
    }

    private static void resetCamera(LocalPlayer player) {
        cameraPlayer = player;
        cameraFloor = null;
        cameraFace = null;
        blendStart = -100;
        fromRoll = shownRoll = 0;
        fromUp = shownUp = WORLD_UP;
    }

    public static RenderPose cameraPose(LivingEntity living, float partial) {
        var target = pose(living, partial);
        if (!(living instanceof LocalPlayer player)) return target;
        boolean blend = !player.isPassenger() && !player.isSleeping() && !OrbitSpaces.isolated(player.level());
        if (player != cameraPlayer || !blend) resetCamera(player);
        if (!blend) return target;
        float time = player.tickCount + partial;
        if (target == null && cameraFloor == null && time >= blendStart + 6) {
            shownUp = WORLD_UP;
            shownRoll = 0;
            return null;
        }
        var frame = GravityFrame.resolve(player);
        var nextFloor = (ClientSubLevel)frame.deck();
        Direction nextFace = nextFloor == null ? null : frame.localUp();
        if (nextFloor != cameraFloor || nextFace != cameraFace) {
            fromUp = shownUp;
            fromRoll = shownRoll;
            cameraFloor = nextFloor;
            cameraFace = nextFace;
            blendStart = time;
        }
        Vec3 up = target == null ? WORLD_UP : Attitude.up(target.body());
        var view = target == null ? Attitude.look(player.getViewYRot(partial), player.getViewXRot(partial)) : target.view();
        var aim = Attitude.forward(view);
        float yaw = Attitude.yaw(aim), pitch = Attitude.pitch(aim);
        float targetRoll = target == null ? 0 : roll(view, yaw, pitch);
        float t = Mth.clamp((time - blendStart) / 6f, 0, 1);
        if (t == 1) {
            shownUp = up;
            shownRoll = targetRoll;
            return target;
        }
        // Ease only the horizon and eye offset. Mouse look still responds on this frame.
        float ease = t * t * (3 - 2 * t);
        shownUp = fromUp.lerp(up, ease).normalize();
        shownRoll = Mth.rotLerp(ease, fromRoll, targetRoll);
        return new RenderPose(new Quaternionf().rotationTo(WORLD_UP.toVector3f(), shownUp.toVector3f()),
            Attitude.look(yaw, pitch).rotateZ(shownRoll * RAD), 0, 0, 0);
    }

    @SubscribeEvent static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (!(event.getCamera().getEntity() instanceof LivingEntity living)) return;
        float partial = (float)event.getPartialTick();
        var pose = cameraPose(living, partial);
        if (pose == null) return;
        Vec3 forward = Attitude.forward(pose.view());
        float yaw = Attitude.yaw(forward), pitch = Attitude.pitch(forward);
        // The residual twist about local +Z stays well-defined while crossing a world pole.
        event.setRoll(event.getRoll() + roll(pose.view(), yaw, pitch));
        event.setYaw(event.getYaw() + Mth.wrapDegrees(yaw - living.getViewYRot(partial)));
        event.setPitch(event.getPitch() + pitch - living.getViewXRot(partial));
    }
}
