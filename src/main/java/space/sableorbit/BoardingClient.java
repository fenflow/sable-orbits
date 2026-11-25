package space.sableorbit;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid="sableorbits",value=Dist.CLIENT)
final class BoardingClient {
    private static Boarding.Target target;
    private static int ticks;
    static void receive(Boarding.Target request) { target = request; ticks = 0; }
    static void cancel(java.util.UUID ticket) {
        if (target != null && target.ticket().equals(ticket)) target = null;
    }

    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { target = null; DeckFrame.clear(); }

    @SubscribeEvent static void unload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel level
                && target != null && target.level() == level.dimension()) target = null;
    }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        DeckFrame.rememberRide(mc.player);
        if (target == null) return;
        if (++ticks >= 200 || mc.player != null && mc.player.isPassenger()) { target = null; return; }
        if (mc.level == null || mc.player == null || mc.level.dimension() != target.level()) return;
        var container = SubLevelContainer.getContainer(mc.level);
        var deck = container == null ? null : container.getSubLevel(target.hull());
        if (deck == null || deck.lastPose().rotationPoint().lengthSquared() == 0) return;
        var feet = deck.logicalPose().transformPosition(target.feet());
        var player = mc.player;
        player.moveTo(feet.x,feet.y,feet.z,player.getYRot(),player.getXRot());
        player.setDeltaMovement(target.onDeck() ? new Vec3(0,-0.04,0) : Vec3.ZERO);
        ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension)player)
            .sable$setTrackingSubLevel(GravityFrame.resolve(player).deck() == deck ? deck : null);
        DeckFrame.board(player,deck);
        PacketDistributor.sendToServer(new Boarding.Ready(target.ticket()));
        target = null;
    }
}
