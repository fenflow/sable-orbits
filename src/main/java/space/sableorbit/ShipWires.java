package space.sableorbit;

import edn.stratodonut.drivebywire.wire.WireNetworkManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

final class ShipWires {
    record Link(BlockPos source, BlockPos sink, Direction face, String channel, int signal) {}
    private static List<Link> links(ServerLevel level) {
        var manager = WireNetworkManager.get(level);
        var links = new ArrayList<Link>();
        manager.getNetwork().forEach((key,channels) -> {
            var source=BlockPos.of(key);
            var signals=manager.getSourceSignals(source);
            channels.forEach((channel,sinks) -> sinks.forEach(sink -> links.add(new Link(source,
                BlockPos.of(sink.position()),Direction.from3DDataValue(sink.direction()),channel,signals.getOrDefault(channel,0)))));
        });
        return links;
    }
    static Map<ServerSubLevel,Set<ServerSubLevel>> adjacency(ServerLevel level) {
        var neighbors = new HashMap<ServerSubLevel,Set<ServerSubLevel>>();
        for (var source : WireNetworkManager.get(level).getNetwork().entrySet()) {
            if (!(Sable.HELPER.getContaining(level, BlockPos.of(source.getKey())) instanceof ServerSubLevel from)) continue;
            for (var sinks : source.getValue().values()) for (var sink : sinks) {
                if (Sable.HELPER.getContaining(level, BlockPos.of(sink.position())) instanceof ServerSubLevel to && to != from) {
                    neighbors.computeIfAbsent(from, part -> new LinkedHashSet<>()).add(to);
                    neighbors.computeIfAbsent(to, part -> new LinkedHashSet<>()).add(from);
                }
            }
        }
        return neighbors;
    }
    static List<Link> onboard(ServerLevel level, Set<ServerSubLevel> members) {
        return links(level).stream().filter(l -> members.contains(Sable.HELPER.getContaining(level,l.source()))
            && members.contains(Sable.HELPER.getContaining(level,l.sink()))).toList();
    }
    static void write(ServerLevel target, List<Link> links, List<Link> staged) {
        for (var l:links) {
            if (!WireNetworkManager.createConnection(target,l.source(),l.sink(),l.face(),l.channel()).isSuccess())
                throw new IllegalStateException("Could not restore wire at "+l.source());
            staged.add(l);
            WireNetworkManager.trySetSignalAt(target,l.source(),l.channel(),l.signal());
        }
    }
    static void clear(ServerLevel level,List<Link> links) {
        for (var l:links) WireNetworkManager.removeConnection(level,l.source(),l.sink(),l.face(),l.channel());
    }
}
