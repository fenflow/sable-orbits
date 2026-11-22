package space.sableorbit;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public final class Spacewalk {
    public static boolean drifting(Entity e) {
        return e instanceof Player p && OrbitSpaces.isolated(p.level()) && !p.isPassenger() && !p.onGround()
            && !p.isSpectator() && !p.isInWaterOrBubble() && !p.isInLava() && !p.onClimbable() && !p.isFallFlying()
            && com.ntmspace.orbit.OrbitEnvironment.gravity(p)==0;
    }
}
