package space.sableorbit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.*;
import java.util.*;

// Search occupied sections in order of their closest possible approach. Empty voxels
// never participate, including the empty space between two wired sublevels.
final class HullDistance {
    record Node(ServerSubLevel part, LevelChunkSection section, int x,int y,int z,int size,AABB box) {
        static Node of(ServerSubLevel part,LevelChunkSection section,int x,int y,int z,int size) {
            var bounds = new BoundingBox3d(x,y,z,x+size,y+size,z+size).transform(part.logicalPose());
            return new Node(part,section,x,y,z,size,new AABB(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ()));
        }
        boolean empty() { return size == 1 && section.getBlockState(x&15,y&15,z&15).isAir(); }
        List<Node> children() {
            int n = size/2;
            var result = new ArrayList<Node>(8);
            for (int dx=0;dx<size;dx+=n) for (int dy=0;dy<size;dy+=n) for (int dz=0;dz<size;dz+=n) {
                var cell = of(part,section,x+dx,y+dy,z+dz,n);
                if (!cell.empty()) result.add(cell);
            }
            return result;
        }
    }
    static List<Node> sections(ServerSubLevel station) {
        var result = new ArrayList<Node>();
        for (var part : Flight.group(station)) for (var holder : part.getPlot().getLoadedChunks()) {
            var chunk = holder.getChunk();
            for (int i=0;i<chunk.getSectionsCount();i++) {
                var section = chunk.getSection(i);
                if (!section.hasOnlyAir()) result.add(Node.of(part,section,chunk.getPos().getMinBlockX(),
                    chunk.getSectionYFromSectionIndex(i)*16,chunk.getPos().getMinBlockZ(),16));
            }
        }
        return result;
    }
    record Pair(Node a,Node b,Vec3 gap) {
        static Pair of(Node a,Node b,Vec3 offset) {
            var x = a.box; var y = b.box.move(offset);
            return new Pair(a,b,new Vec3(delta(x.minX,x.maxX,y.minX,y.maxX),
                delta(x.minY,x.maxY,y.minY,y.maxY),delta(x.minZ,x.maxZ,y.minZ,y.maxZ)));
        }
        static double delta(double a0,double a1,double b0,double b1) {
            return b0 > a1 ? b0-a1 : b1 < a0 ? b1-a0 : 0;
        }
    }
    // The returned vector is directed from host to guest. A null result means the
    // closest occupied blocks are beyond the supplied search radius.
    static Vec3 closest(List<Node> host,List<Node> guest,Vec3 offset,double radius) {
        var queue = new PriorityQueue<Pair>(Comparator.<Pair>comparingDouble(p -> p.gap.lengthSqr()).thenComparingInt(p -> p.a.size+p.b.size));
        double limit = radius*radius;
        for (var a : host) for (var b : guest) {
            var pair = Pair.of(a,b,offset);
            if (pair.gap.lengthSqr() <= limit) queue.add(pair);
        }
        while (!queue.isEmpty()) {
            var p = queue.remove();
            if (p.a.size == 1 && p.b.size == 1) return p.gap;
            boolean splitA = p.a.size >= p.b.size;
            for (var child : (splitA?p.a:p.b).children()) {
                var next = splitA ? Pair.of(child,p.b,offset) : Pair.of(p.a,child,offset);
                if (next.gap.lengthSqr() <= limit) queue.add(next);
            }
        }
        return null;
    }
}

