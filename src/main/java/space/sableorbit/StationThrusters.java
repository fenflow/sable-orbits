package space.sableorbit;

import com.hbm.lib.ForgeDirection;
import com.hbm.tileentity.MachineCore;
import com.ntmspace.orbit.OrbitalStation;
import com.ntmspace.orbit.Propulsion;
import com.ntmspace.tileentity.station.TileEntityMachineHTR3;
import com.ntmspace.tileentity.station.TileEntityMachineHTRF4;
import com.ntmspace.tileentity.station.TileEntityMachineHTRNeo;
import com.ntmspace.tileentity.station.TileEntityMachineLPW2Space;
import com.ntmspace.tileentity.station.TileEntityXenonThruster;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import space.sableorbit.mixin.EquipmentAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StationThrusters {
    private static final double ALIGNMENT_DOT = Math.cos(Math.toRadians(1));

    public interface Alignment {
        boolean sableorbits$wrongDirection();
        void sableorbits$wrongDirection(boolean wrong);
    }

    record Thruster(Propulsion engine, ServerSubLevel plot) {
        Vec3 exhaustDirection() {
            var tile = engine.getBlockEntity();
            // Xenon's nozzle follows the core facing. The other models use its clockwise quarter turn.
            var facing = MachineCore.facing(tile.getBlockState());
            var exhaust = tile instanceof TileEntityXenonThruster ? facing : facing.getRotation(ForgeDirection.UP);
            return plot.logicalPose().transformNormal(new Vec3(exhaust.offsetX, exhaust.offsetY, exhaust.offsetZ)).normalize();
        }
    }

    private record Heading(Vec3 exhaust, List<Thruster> engines) {}

    public static boolean burning(BlockEntity tile) {
        return switch (tile) {
            case TileEntityXenonThruster thruster -> thruster.isOn;
            case TileEntityMachineHTR3 thruster -> thruster.isOn;
            case TileEntityMachineHTRF4 thruster -> thruster.isOn;
            case TileEntityMachineHTRNeo thruster -> thruster.isOn;
            case TileEntityMachineLPW2Space thruster -> thruster.isOn;
            case null, default -> false;
        };
    }

    static List<Propulsion> refresh(OrbitalStation station, List<Thruster> thrusters) {
        if (thrusters.isEmpty()) return List.of();
        // In a tie, the lowest plot block position chooses the reference direction.
        thrusters.sort(Comparator.comparing(thruster -> thruster.engine().getBlockEntity().getBlockPos()));
        var headings = new ArrayList<Heading>();
        Heading preferred = null;
        for (var thruster : thrusters) {
            var exhaust = thruster.exhaustDirection();
            Heading heading = null;
            for (var candidate : headings) {
                if (candidate.exhaust().dot(exhaust) >= ALIGNMENT_DOT) {
                    heading = candidate;
                    break;
                }
            }
            if (heading == null) {
                heading = new Heading(exhaust, new ArrayList<>());
                headings.add(heading);
            }
            heading.engines().add(thruster);
        }
        for (var heading : headings) {
            if (preferred == null || heading.engines().size() > preferred.engines().size()) preferred = heading;
        }

        var engines = ((EquipmentAccess)(Object)station).sableorbits$engines();
        boolean inBurn = station.state == OrbitalStation.StationState.LEAVING || station.state == OrbitalStation.StationState.TRANSFER;
        List<Propulsion> excludedBurns = List.of();
        for (var heading : headings) for (var thruster : heading.engines()) {
            boolean wrong = heading != preferred;
            var tile = thruster.engine().getBlockEntity();
            var alignment = (Alignment)tile;
            if (alignment.sableorbits$wrongDirection() != wrong) {
                alignment.sableorbits$wrongDirection(wrong);
                thruster.plot().getLevel().getChunkSource().blockChanged(tile.getBlockPos());
            }
            // Preserve the departure burn if a plot turns during flight or is restored from a save.
            if (inBurn ? !burning(tile) : wrong) engines.remove(thruster.engine());
            if (wrong && station.state == OrbitalStation.StationState.ORBIT && burning(tile)) {
                if (excludedBurns.isEmpty()) excludedBurns = new ArrayList<>();
                excludedBurns.add(thruster.engine());
            }
        }
        return excludedBurns;
    }
}
