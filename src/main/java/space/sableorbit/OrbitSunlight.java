package space.sableorbit;

import com.ntmspace.tileentity.machine.TileEntityMachineSolarPanel;
import com.ntmspace.util.SkyColumns;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

public final class OrbitSunlight {
    private static final int FULL_SKY_OUTPUT = 4; // The native panel uses unobstructed sky light (15) minus 11.

    public interface EclipseClock {
        double sableorbits$eclipseAt(long gameTime);
    }

    public static void generate(ServerLevel level, BlockPos pos, TileEntityMachineSolarPanel panel) {
        long current = panel.getPower();
        long capacity = panel.getMaxPower();
        if (current == capacity) return;
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel plot)) return;
        var station = Stations.nativeStation(plot);
        if (station == null) return;

        // The native orbital sky and panel use the same celestial clock and eclipse calculation.
        var clock = (EclipseClock)(Object)station;
        double visible = 1 - clock.sableorbits$eclipseAt(level.getGameTime());
        if (visible <= 0 || pos.getY() + 1 < SkyColumns.lowestSourceY(level.getChunkAt(pos), pos.getX(), pos.getZ())) return;

        float sunlight = station.orbiting.getSunPower();
        double progress = station.getTransferProgress(0);
        if (progress > 0) sunlight = (float) Mth.lerp(progress, sunlight, station.target.getSunPower());
        long power = Math.min(capacity, current + panel.getOutput(FULL_SKY_OUTPUT, (float)(sunlight * visible)));
        if (power != current) {
            panel.setPower(power);
            panel.setChanged();
        }
    }
}
