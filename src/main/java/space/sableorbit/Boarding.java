package space.sableorbit;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
    record Cancel(UUID ticket) implements CustomPacketPayload {
        static final Type<Cancel> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sableorbits","board_cancel"));
        static final StreamCodec<RegistryFriendlyByteBuf,Cancel> CODEC = StreamCodec.of((buf,c)->buf.writeUUID(c.ticket),buf->new Cancel(buf.readUUID()));
        public Type<Cancel> type() { return TYPE; }
    }
    private record Pending(Target target, int expires) {}
    private static final Map<ServerPlayer,Pending> pending = new WeakHashMap<>();

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Target.TYPE,Target.CODEC,(target,ctx)->BoardingClient.receive(target));
        registrar.playToClient(Cancel.TYPE,Cancel.CODEC,(cancel,ctx)->BoardingClient.cancel(cancel.ticket()));
        registrar.playToServer(Ready.TYPE,Ready.CODEC,(ready,ctx)->{
            if (ctx.player() instanceof ServerPlayer player) {
                var arrival = current(player);
                if (arrival != null && arrival.target.ticket.equals(ready.ticket)) {
                    follow(player);
                    if (pending.remove(player) != null) {
                        var deck = GravityFrame.resolve(player).deck();
                        if (deck != null && deck.getUniqueId().equals(arrival.target.hull))
                            ((EntityMovementExtension)player).sable$setTrackingSubLevel(deck);
                    }
                }
            }
        });
    }

    static boolean arrive(ServerPlayer player, ServerSubLevel deck, Vec3 feet, boolean onDeck) {
        var level = deck.getLevel();
        player.teleportTo(level,feet.x,feet.y,feet.z,player.getYRot(),player.getXRot());
        // Dimension travel can be vetoed even though teleportTo returns void.
        if (player.serverLevel() != level || deck.isRemoved() || player.position().distanceToSqr(feet) > 1e-8) return false;
        followOnLoad(player, deck, feet, onDeck);
        return true;
    }

    static void followOnLoad(ServerPlayer player, ServerSubLevel deck, Vec3 feet, boolean onDeck) {
        var target = new Target(deck.getLevel().dimension(),deck.getUniqueId(),deck.logicalPose().transformPositionInverse(feet),onDeck,UUID.randomUUID());
        Spacewalk.remember(player,deck);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        if (player.connection != null && player.connection.hasChannel(Target.TYPE)) {
            pending.put(player,new Pending(target,player.tickCount+200));
            PacketDistributor.sendToPlayer(player,target);
        }
    }

    static void forget(ServerPlayer player) { pending.remove(player); }

    public static void cancelForTeleport(ServerPlayer player) {
        var arrival = pending.remove(player);
        if (arrival != null && player.connection.hasChannel(Cancel.TYPE))
            PacketDistributor.sendToPlayer(player, new Cancel(arrival.target.ticket));
    }

    private static Pending current(ServerPlayer player) {
        var arrival = pending.get(player);
        if (arrival != null && (arrival.target.level != player.level().dimension() || player.tickCount >= arrival.expires
                || player.isPassenger())) {
            forget(player);
            return null;
        }
        return arrival;
    }

    public static boolean pending(ServerPlayer player) { return current(player) != null; }

    static Vec3 pendingFeet(ServerPlayer player, SubLevel deck) {
        var arrival = current(player);
        return arrival != null && arrival.target.hull.equals(deck.getUniqueId()) ? arrival.target.feet : null;
    }

    static void follow(ServerPlayer player) {
        var arrival = current(player);
        if (arrival == null) return;
        var target = arrival.target;
        var container = SubLevelContainer.getContainer(player.level());
        var deck = container == null ? null : container.getSubLevel(target.hull);
        if (deck == null || deck.isRemoved()) {
            forget(player);
            return;
        }
        // Keep the server's chunk tracking and reentry checks at the live boarding point while the plot loads.
        var feet = deck.logicalPose().transformPosition(target.feet);
        player.setPos(feet);
        Spacewalk.remember(player,deck);
        player.fallDistance = 0;
    }
}
