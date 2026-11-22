package space.sableorbit;

import com.ntmspace.orbit.Orbit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod("sableorbits")
public class SableOrbits {
    public SableOrbits(net.neoforged.bus.api.IEventBus bus) {
        ConsoleMenus.MENUS.register(bus);
        Engines.register(bus);
        Attitude.register(bus);
        GravityFrame.register(bus);
        LaunchMotion.TYPES.register(bus);
        bus.addListener(FlightVelocity::register);
        bus.addListener(Boarding::register);
        bus.addListener(EvaMove::register);
        Stations.initialize();
        NeoForge.EVENT_BUS.addListener(Attitude::tick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> StationLife.tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(StationCommand::register);
        NeoForge.EVENT_BUS.addListener(Engines::place);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> OrbitSpaces.restore(e.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                Stations.forgetSky(player);
                Spacewalk.forget(player);
                Attitude.reset(player);
                var previous = player.serverLevel().getServer().getLevel(e.getFrom());
                if (OrbitSpaces.isolated(player.level()) || (previous != null && OrbitSpaces.isolated(previous)))
                    player.getPersistentData().putLong("sableorbits_ordered_until", player.serverLevel().getServer().overworld().getGameTime() + 60);
                // Sable removal packets identify a plot by index only. The respawn packet
                // already clears the client container before its new dimension starts tracking.
                if (previous != null) for (var plot : Stations.loaded(previous))
                    plot.getTrackingPlayers().remove(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel level) Stations.tick(level);
        });
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent e) -> {
            if (e.getEntity() instanceof Player p && Orbit.inOrbit(p.level()) && e.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) e.setCanceled(true);
        });
    }
}
