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
    static List<Link> links(ServerLevel level) {
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
    static void neighbors(ServerSubLevel part, Collection<ServerSubLevel> queue, List<Link> links) {
        var level=part.getLevel();
        for (var link:links) {
            var a=Sable.HELPER.getContaining(level,link.source());
            var b=Sable.HELPER.getContaining(level,link.sink());
            if (a==part && b instanceof ServerSubLevel s && b!=part) queue.add(s);
            if (b==part && a instanceof ServerSubLevel s && a!=part) queue.add(s);
        }
    }
    static List<Link> onboard(ServerLevel level, Set<ServerSubLevel> members) {
        return links(level).stream().filter(l -> members.contains(Sable.HELPER.getContaining(level,l.source()))
            && members.contains(Sable.HELPER.getContaining(level,l.sink()))).toList();
    }
    static void write(ServerLevel target,List<Link> links) {
        for (var l:links) {
            WireNetworkManager.createConnection(target,l.source(),l.sink(),l.face(),l.channel());
            if (!WireNetworkManager.hasConnection(target,l.source(),l.sink(),l.face(),l.channel()))
                throw new IllegalStateException("Could not restore wire at "+l.source());
            WireNetworkManager.trySetSignalAt(target,l.source(),l.channel(),l.signal());
        }
    }
    static void clear(ServerLevel level,List<Link> links) {
        for (var l:links) WireNetworkManager.removeConnection(level,l.source(),l.sink(),l.face(),l.channel());
    }
}
