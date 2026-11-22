package space.sableorbit.mixin;

import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import space.sableorbit.OrbitSpaces;

@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public class TrackingMixin {
    // Movement packets address reusable plot indices. Order them with the respawn and
    // tracking packets when a player can cross an orbital dimension boundary.
    @Redirect(method = "sendMovementUpdates", at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/network/udp/SableUDPServer;isConnectedTo(Lnet/minecraft/server/level/ServerPlayer;)Z"))
    private boolean sableorbits$useUdp(SableUDPServer udp, ServerPlayer player) {
        if (OrbitSpaces.isolated(player.level()) || player.getY() >= 1800) return false;
        return player.serverLevel().getServer().overworld().getGameTime() >=
            player.getPersistentData().getLong("sableorbits_ordered_until") && udp.isConnectedTo(player);
    }
}
