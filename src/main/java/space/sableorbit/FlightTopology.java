package space.sableorbit;

import com.hbm.tileentity.network.TileEntityPylonBase;
import com.ntmspace.orbit.Propulsion;
import com.ntmspace.tileentity.station.TileEntityOrbitalStation;
import com.ntmspace.tileentity.station.TileEntityOrbitalStationComputer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reuses plot bounds, wiring and machine lookups within one server operation. */
final class FlightTopology {
    private final ServerLevel level;
    private final Map<ServerSubLevel, List<SubLevel>> neighbors = new IdentityHashMap<>();
    private final Map<ServerSubLevel, Equipment> equipment = new IdentityHashMap<>();
    private Map<ServerSubLevel, Set<ServerSubLevel>> wires;
    private PlotBounds bounds;

    FlightTopology(ServerLevel level) { this.level = level; }

    record Equipment(List<TileEntityOrbitalStation> ports, List<Propulsion> engines,
                     List<TileEntityPylonBase> pylons, boolean computer) {}

    Equipment equipment(ServerSubLevel part) {
        return equipment.computeIfAbsent(part, FlightTopology::scanEquipment);
    }

    private static Equipment scanEquipment(ServerSubLevel part) {
        var ports = new ArrayList<TileEntityOrbitalStation>();
        var engines = new ArrayList<Propulsion>();
        var pylons = new ArrayList<TileEntityPylonBase>();
        boolean computer = false;
        for (var holder : part.getPlot().getLoadedChunks()) {
            for (var tile : holder.getChunk().getBlockEntities().values()) {
                if (tile.isRemoved()) continue;
                if (tile instanceof TileEntityOrbitalStation port) ports.add(port);
                if (tile instanceof Propulsion engine) engines.add(engine);
                if (tile instanceof TileEntityPylonBase pylon) pylons.add(pylon);
                if (tile instanceof TileEntityOrbitalStationComputer) computer = true;
            }
        }
        return new Equipment(ports, engines, pylons, computer);
    }

    List<SubLevel> neighbors(ServerSubLevel part) {
        return neighbors.computeIfAbsent(part, this::findNeighbors);
    }

    private List<SubLevel> findNeighbors(ServerSubLevel part) {
        if (wires == null) wires = Flight.wiresLoaded() ? ShipWires.adjacency(level) : Map.of();
        if (bounds == null) {
            var plots = Stations.loaded(level);
            var indexed = new ArrayList<Plot>(plots.size());
            for (var plot : plots) indexed.add(new Plot(plot, new BoundingBox3d(plot.boundingBox()), indexed.size()));
            bounds = PlotBounds.build(indexed);
        }
        var connected = new ArrayList<SubLevel>(wires.getOrDefault(part, Set.of()));
        var intersecting = new ArrayList<Plot>();
        bounds.intersections(part.boundingBox(), intersecting);
        // Keep Sable's traversal order, including when two roots discover a fragment together.
        intersecting.sort(Comparator.comparingInt(Plot::order));
        for (var plot : intersecting) connected.add(plot.part());
        for (var actor : part.getPlot().getBlockEntityActors()) {
            var dependencies = actor.sable$getLoadingDependencies();
            if (dependencies != null) dependencies.forEach(connected::add);
        }
        for (var pylon : equipment(part).pylons()) {
            for (var peer : pylon.getConnected()) {
                var plot = Sable.HELPER.getContaining(level, peer);
                if (plot != null) connected.add(plot);
            }
        }
        return connected;
    }

    private record Plot(ServerSubLevel part, BoundingBox3d box, int order) {}

    private record PlotBounds(BoundingBox3d box, List<Plot> plots, PlotBounds left, PlotBounds right) {
        static PlotBounds build(List<Plot> plots) {
            if (plots.isEmpty()) return new PlotBounds(null, List.of(), null, null);
            var enclosing = new BoundingBox3d(plots.getFirst().box());
            for (int i = 1; i < plots.size(); i++) enclosing.expandTo(plots.get(i).box(), enclosing);
            if (plots.size() <= 8) return new PlotBounds(enclosing, plots, null, null);
            if (enclosing.width() >= enclosing.height() && enclosing.width() >= enclosing.length())
                plots.sort(Comparator.comparingDouble(plot -> plot.box().minX() + plot.box().maxX()));
            else if (enclosing.height() >= enclosing.length())
                plots.sort(Comparator.comparingDouble(plot -> plot.box().minY() + plot.box().maxY()));
            else plots.sort(Comparator.comparingDouble(plot -> plot.box().minZ() + plot.box().maxZ()));
            int middle = plots.size() / 2;
            return new PlotBounds(enclosing, List.of(), build(plots.subList(0, middle)), build(plots.subList(middle, plots.size())));
        }

        void intersections(BoundingBox3dc target, List<Plot> hits) {
            if (box == null || !box.intersects(target)) return;
            if (left != null) {
                left.intersections(target, hits);
                right.intersections(target, hits);
            } else {
                for (var plot : plots) if (plot.box().intersects(target)) hits.add(plot);
            }
        }
    }
}
