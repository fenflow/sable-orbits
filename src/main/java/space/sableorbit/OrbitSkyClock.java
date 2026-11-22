package space.sableorbit;

import com.ntmspace.orbit.OrbitalStation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid="sableorbits", value=Dist.CLIENT)
public final class OrbitSkyClock {
    @SubscribeEvent public static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ClientLevel level) || !OrbitSpaces.isolated(level)) return;
        var sky = OrbitalStation.clientStation;
        if (sky != null && sky.state != OrbitalStation.StationState.ORBIT && sky.stateTimer < sky.maxStateTimer)
            sky.update(level);
    }
}
