package space.sableorbit;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;

public record EvaMove(ResourceKey<Level> dimension, UUID hull, double releaseTime, double displayTime,
                      Vec3 displacement, float yaw, float pitch, boolean grounded) implements CustomPacketPayload {
    public static final Type<EvaMove> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sableorbits","inertial_eva"));
    private static final StreamCodec<RegistryFriendlyByteBuf,EvaMove> CODEC = StreamCodec.of(
        (buf, move) -> {
            buf.writeResourceKey(move.dimension);
            buf.writeUUID(move.hull);
            buf.writeDouble(move.releaseTime);
            buf.writeDouble(move.displayTime);
            buf.writeVec3(move.displacement);
            buf.writeFloat(move.yaw);
            buf.writeFloat(move.pitch);
            buf.writeBoolean(move.grounded);
        },
        buf -> new EvaMove(buf.readResourceKey(net.minecraft.core.registries.Registries.DIMENSION), buf.readUUID(),
            buf.readDouble(), buf.readDouble(), buf.readVec3(), buf.readFloat(), buf.readFloat(), buf.readBoolean()));
    public Type<EvaMove> type() { return TYPE; }

    public interface Connection {
        void sableorbits$rejectDeparture(EvaMove move);
    }

    public static final class Recovery {
        private EvaMove rejected;
        private int firstTick;
        private long nextCorrection;
        private boolean corrected;

        public void accepted() { rejected = null; }

        public void teleported(int tick) { nextCorrection = (long)tick + 20; }

        public boolean needsCorrection(EvaMove move, int tick) {
            if (rejected == null || !rejected.hull.equals(move.hull) || rejected.releaseTime != move.releaseTime
                    || rejected.dimension != move.dimension) {
                rejected = move;
                firstTick = tick;
                corrected = false;
            }
            // A delayed packet can cross a boarding handoff. Only reconcile a
            // departure the client is still using several server ticks later.
            if (corrected || tick-firstTick < 5 || tick < nextCorrection) return false;
            corrected = true;
            return true;
        }
    }

    public static final class Projected extends ServerboundMovePlayerPacket.PosRot {
        public final double relativeDistanceSquared;
        Projected(EvaMove move, Vec3 point, double relativeDistanceSquared) {
            super(point.x,point.y,point.z,move.yaw,move.pitch,move.grounded);
            this.relativeDistanceSquared = relativeDistanceSquared;
        }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE,CODEC,(move,ctx)->{
            if (ctx.player() instanceof ServerPlayer player) move.accept(player);
        });
    }

    private void accept(ServerPlayer player) {
        if (player.isPassenger() || player.isSpectator() || !OrbitSpaces.isolated(player.level()) || player.level().dimension()!=dimension
                || !Double.isFinite(releaseTime) || !Double.isFinite(displayTime) || !Double.isFinite(displacement.lengthSqr())
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Boarding.pending(player)) return;
        if (!project(player)) ((Connection)player.connection).sableorbits$rejectDeparture(this);
    }

    private boolean project(ServerPlayer player) {
        var ref = Spacewalk.reference(player);
        if (ref == null || !ref.hull.equals(hull)) return false;
        int now = Spacewalk.clock(player);
        var sample = ref.contactAt(releaseTime,displayTime,now,player.connection.latency());
        if (sample == null) return false;

        // A brief landing can give us just one server contact, several ticks ahead
        // of the rendered deck. Measure both displacements over the client's same
        // elapsed interval, then place the result on the server's current trajectory.
        var offset = sample.relativeDisplacement(displacement, displayTime-releaseTime);
        var point = sample.at(now).add(offset);
        if (!Double.isFinite(point.lengthSqr()) || Math.abs(point.x)>3.0e7 || Math.abs(point.y)>2.0e7 || Math.abs(point.z)>3.0e7
                || ref.departure == null && !ref.reachableAtRelease(sample,point,player.position(),now)) return false;
        double relativeDistanceSquared = offset.distanceToSqr(ref.startOfTick(player.tickCount));
        if (!Double.isFinite(relativeDistanceSquared)) return false;
        var packet = new Projected(this,point,relativeDistanceSquared);
        player.connection.handleMovePlayer(packet);
        if (player.position().distanceToSqr(point) < 1.0e-8) {
            ref.world = player.position();
            if (ref.departure != null && displayTime-ref.lastDisplay >= 0.5)
                ref.steeringVelocity = offset.subtract(ref.offset).scale(1/(displayTime-ref.lastDisplay));
            ref.offset = offset;
            ref.lastMove = player.tickCount;
            ref.positionClock = now;
            ref.lastDisplay = displayTime;
            if (ref.departure == null) {
                ref.departure = sample;
                ref.releaseTime = releaseTime;
                ref.deck = null;
                ref.history.clear();
            }
        }
        return true; // Vanilla owns any collision or speed correction after projection.
    }
}
