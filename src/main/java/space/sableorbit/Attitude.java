package space.sableorbit;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.function.Supplier;

/** Actor-local +X is left, +Y is up and +Z is forward, as in vanilla movement input. */
public final class Attitude {
    private static final float RAD = (float)(Math.PI / 180);
    private static final DeferredRegister<AttachmentType<?>> TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "sableorbits");
    private static final Supplier<AttachmentType<Quaternionf>> ORIENTATION = TYPES.register("eva_orientation", () ->
        AttachmentType.builder(() -> new Quaternionf()).serialize(ExtraCodecs.QUATERNIONF_COMPONENTS)
            .sync(StreamCodec.of((buf, q) -> buf.writeQuaternion(q), buf -> buf.readQuaternion())).build());

    private static final class Input {
        Quaternionf predicted, sent;
        int lastSend = Integer.MIN_VALUE;
        Turn pending;
    }

    private static final Supplier<AttachmentType<Input>> INPUT = TYPES.register("attitude_input", () ->
        AttachmentType.builder(Input::new).build());

    static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener(Attitude::registerPayload);
    }

    public static Quaternionf look(float worldYaw, float worldPitch) {
        return new Quaternionf().rotationY(-worldYaw * RAD).rotateX(worldPitch * RAD);
    }

    public static Vec3 forward(Quaternionfc orientation) {
        return new Vec3(orientation.transformUnitPositiveZ(new Vector3f()));
    }

    public static Vec3 up(Quaternionfc orientation) {
        return new Vec3(orientation.transformUnitPositiveY(new Vector3f()));
    }

    public static float yaw(Vec3 forward) {
        return (float)Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    public static float pitch(Vec3 forward) {
        return (float)Math.toDegrees(Math.atan2(-forward.y, forward.horizontalDistance()));
    }

    public static boolean free(Player player) {
        return GravityFrame.resolve(player).free();
    }

    public static Quaternionf freeLook(Player player) {
        var input = player.getExistingDataOrNull(INPUT);
        if (input != null && input.predicted != null) return new Quaternionf(input.predicted);
        var saved = player.getExistingDataOrNull(ORIENTATION);
        return saved != null && valid(saved) ? new Quaternionf(saved).normalize() : look(player.getYRot(), player.getXRot());
    }

    public static Quaternionf deckBasis(GravityFrame frame) {
        var normal = frame.localUp().step();
        return new Quaternionf(frame.deck().logicalPose().orientation())
            .mul(new Quaternionf().rotationTo(0, 1, 0, normal.x, normal.y, normal.z));
    }

    public static Quaternionf deckBody(LivingEntity entity, Vec3 normal, float worldYaw) {
        var aim = forward(look(worldYaw, entity.getXRot()));
        var tangent = aim.subtract(normal.scale(aim.dot(normal)));
        if (tangent.lengthSqr() < 1.0e-9) {
            // At a view pole the camera's horizontal left still identifies its heading.
            var left = look(worldYaw, 0).transformUnitPositiveX(new Vector3f());
            tangent = new Vec3(left).cross(normal);
        }
        return new Quaternionf().lookAlong(tangent.normalize().scale(-1).toVector3f(), normal.toVector3f()).conjugate();
    }

    public static Quaternionf body(LivingEntity entity) {
        var frame = GravityFrame.resolve(entity);
        if (entity instanceof Player player) {
            if (frame.free()) return freeLook(player);
            if (frame.deck() != null) return deckBody(player, frame.up(), player.getYRot());
        } else if (frame.deck() != null) {
            return deckBasis(frame).rotateY(-entity.yBodyRot * RAD);
        }
        return look(entity.yBodyRot, 0);
    }

    public static void setLocal(Player player, Quaternionfc orientation) {
        if (!player.level().isClientSide || !free(player) || !valid(orientation)) return;
        player.getData(INPUT).predicted = new Quaternionf(orientation).normalize();
        BodyBox.refresh(player);
    }

    private static final ResourceLocation CREATE_SEAT = ResourceLocation.fromNamespaceAndPath("create", "seat");

    public static Quaternionf seatedLook(Player player) {
        var seat = player.getVehicle();
        if (seat == null || !OrbitSpaces.isolated(player.level())
                || !CREATE_SEAT.equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(seat.getType()))) return null;
        var deck = dev.ryanhcode.sable.Sable.HELPER.getContaining(seat);
        if (deck == null || deck.isRemoved()) return null;
        // Sable keeps mounted look angles in plot axes. Snapshot before its dismount hook
        // projects the look direction into two world angles and discards the twist.
        var pose = deck instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientDeck
            ? clientDeck.renderPose() : deck.logicalPose();
        return new Quaternionf(pose.orientation()).mul(look(player.getYRot(), player.getXRot())).normalize();
    }

    public static void leaveSeat(Player player, Quaternionf view) {
        if (view == null || player.isPassenger()) return;
        if (player instanceof ServerPlayer) GravityFrame.update(player);
        if (!free(player)) return;
        var direction = forward(view);
        player.setYRot(yaw(direction));
        player.setXRot(pitch(direction));
        if (player instanceof ServerPlayer) {
            player.setData(ORIENTATION, view);
            BodyBox.refresh(player);
        } else if (player.isLocalPlayer()) {
            setLocal(player, view);
        }
    }

    public static void sendLocal(Player player) {
        var input = player.getExistingDataOrNull(INPUT);
        if (input == null || input.predicted == null || input.lastSend == player.tickCount || !free(player)) return;
        if (input.sent != null && equivalent(input.sent, input.predicted)) return;
        input.sent = new Quaternionf(input.predicted);
        input.lastSend = player.tickCount;
        PacketDistributor.sendToServer(new Turn(player.level().dimension(), input.sent));
    }

    private static boolean equivalent(Quaternionfc a, Quaternionfc b) {
        // q and -q describe the same rotation.
        return Math.abs(a.x()*b.x() + a.y()*b.y() + a.z()*b.z() + a.w()*b.w()) >= 1.0f - 1.0e-7f;
    }

    static boolean valid(Quaternionfc q) {
        float norm = q.lengthSquared();
        return norm > 0.99f && norm < 1.01f;
    }

    private record Turn(ResourceKey<Level> dimension, Quaternionf orientation) implements CustomPacketPayload {
        static final Type<Turn> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sableorbits", "turn"));
        static final StreamCodec<RegistryFriendlyByteBuf, Turn> CODEC = StreamCodec.of((buf, turn) -> {
            buf.writeResourceKey(turn.dimension);
            buf.writeQuaternion(turn.orientation);
        }, buf -> new Turn(buf.readResourceKey(Registries.DIMENSION), buf.readQuaternion()));
        public Type<Turn> type() { return TYPE; }
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Turn.TYPE, Turn.CODEC, (turn, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player && player.level().dimension() == turn.dimension
                    && OrbitSpaces.isolated(player.level()) && valid(turn.orientation)) {
                // Several mouse samples can arrive together. Keep only the newest for the simulation tick.
                player.getData(INPUT).pending = turn;
            }
        });
    }

    static void reset(Player player) {
        if (player.hasData(INPUT)) player.removeData(INPUT);
        if (player.hasData(ORIENTATION)) player.removeData(ORIENTATION);
    }

    static void tick(EntityTickEvent.Post event) {
        var entity = event.getEntity();
        if (!(entity instanceof LivingEntity) && !GravityFrame.nonliving(entity)) return;
        if (entity instanceof Player player && (player.hasData(INPUT) || player.hasData(ORIENTATION))) {
            if (!free(player)) reset(player);
            else if (player instanceof ServerPlayer) {
                var input = player.getExistingDataOrNull(INPUT);
                if (input != null && input.pending != null) {
                    Turn turn = input.pending;
                    input.pending = null;
                    var old = player.getExistingDataOrNull(ORIENTATION);
                    if (player.level().dimension() == turn.dimension && (old == null || !equivalent(old, turn.orientation)))
                        player.setData(ORIENTATION, turn.orientation.normalize());
                }
            }
        }
        BodyBox.refresh(entity);
    }
}
