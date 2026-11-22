package space.sableorbit;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

public final class Boarding {
    record Target(ResourceKey<Level> level, UUID hull, Vec3 feet, boolean onDeck, UUID ticket) implements CustomPacketPayload {
        static final Type<Target> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sableorbits","board"));
        static final StreamCodec<RegistryFriendlyByteBuf,Target> CODEC = StreamCodec.of(
            (buf, target) -> {
                buf.writeResourceKey(target.level);
                buf.writeUUID(target.hull);
                buf.writeVec3(target.feet);
                buf.writeBoolean(target.onDeck);
                buf.writeUUID(target.ticket);
            },
            buf -> new Target(buf.readResourceKey(net.minecraft.core.registries.Registries.DIMENSION),buf.readUUID(),buf.readVec3(),buf.readBoolean(),buf.readUUID()));
        public Type<Target> type() { return TYPE; }
    }
    record Ready(UUID ticket) implements CustomPacketPayload {
        static final Type<Ready> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sableorbits","board_ready"));
        static final StreamCodec<RegistryFriendlyByteBuf,Ready> CODEC = StreamCodec.of((buf,r)->buf.writeUUID(r.ticket),buf->new Ready(buf.readUUID()));
        public Type<Ready> type() { return TYPE; }
    }
    private record Pending(Target target, int expires) {}
    private static final Map<ServerPlayer,Pending> pending = new WeakHashMap<>();

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Target.TYPE,Target.CODEC,(target,ctx)->BoardingClient.receive(target));
        registrar.playToServer(Ready.TYPE,Ready.CODEC,(ready,ctx)->{
            if (ctx.player() instanceof ServerPlayer player) {
                var arrival = pending.get(player);
                if (arrival != null && arrival.target.ticket.equals(ready.ticket) && arrival.target.level == player.level().dimension()) {
                    follow(player);
                    pending.remove(player);
                }
            }
        });
    }

    static void arrive(ServerPlayer player, ServerSubLevel deck, Vec3 feet, boolean onDeck) {
        var level = deck.getLevel();
        var target = new Target(level.dimension(),deck.getUniqueId(),deck.logicalPose().transformPositionInverse(feet),onDeck,UUID.randomUUID());
        player.teleportTo(level,feet.x,feet.y,feet.z,player.getYRot(),player.getXRot());
        Spacewalk.remember(player,deck);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        if (player.connection != null && player.connection.hasChannel(Target.TYPE)) {
            pending.put(player,new Pending(target,player.tickCount+200));
            PacketDistributor.sendToPlayer(player,target);
        }
    }

    public static boolean pending(ServerPlayer player) { return pending.containsKey(player); }

    static void follow(ServerPlayer player) {
        var arrival = pending.get(player);
        if (arrival == null) return;
        var target = arrival.target;
        var container = SubLevelContainer.getContainer(player.level());
        var deck = container == null ? null : container.getSubLevel(target.hull);
        if (player.tickCount >= arrival.expires || player.level().dimension() != target.level || deck == null || player.isPassenger()) {
            pending.remove(player);
            return;
        }
        // Keep the server's chunk tracking and reentry checks at the live boarding point while the plot loads.
        var feet = deck.logicalPose().transformPosition(target.feet);
        player.setPos(feet);
        Spacewalk.remember(player,deck);
        player.fallDistance = 0;
    }
}
