package space.sableorbit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

// Search occupied sections in order of their closest possible approach. Empty voxels
// never participate, including the empty space between two wired sublevels.
final class HullDistance {
    private interface Region {
        AABB box();
        int size();
        List<? extends Region> children();
    }

    private record SectionBranch(AABB box, int size, List<Region> children) implements Region {}

    static final class Node implements Region {
        private final ServerSubLevel part;
        private final LevelChunkSection section;
        private final Pose3d pose;
        private final int x, y, z, size;
        private final AABB box;
        private final boolean full;
        private List<Node> children;

        private Node(ServerSubLevel part, LevelChunkSection section, Pose3d pose, int x, int y, int z, int size, boolean full) {
            this.part = part;
            this.section = section;
            this.pose = pose;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.full = full;
            var bounds = new BoundingBox3d(x, y, z, x + size, y + size, z + size).transform(pose);
            box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
        }
        ServerSubLevel part() { return part; }
        LevelChunkSection section() { return section; }
        int x() { return x; }
        int y() { return y; }
        int z() { return z; }
        @Override public AABB box() { return box; }
        @Override public int size() { return size; }

        @Override public List<Node> children() {
            if (children != null) return children;
            if (size == 1) return List.of();
            int half = size / 2;
            children = new ArrayList<>(8);
            for (int dx = 0; dx < size; dx += half) {
                for (int dy = 0; dy < size; dy += half) {
                    for (int dz = 0; dz < size; dz += half) {
                        int childX = x + dx, childY = y + dy, childZ = z + dz;
                        if (full || occupied(childX & 15, childY & 15, childZ & 15, half)) {
                            children.add(new Node(part, section, pose, childX, childY, childZ, half, full));
                        }
                    }
                }
            }
            return children;
        }

        private boolean occupied(int minX, int minY, int minZ, int width) {
            for (int y = minY; y < minY + width; y++) {
                for (int z = minZ; z < minZ + width; z++) {
                    for (int x = minX; x < minX + width; x++) {
                        if (!section.getBlockState(x, y, z).isAir()) return true;
                    }
                }
            }
            return false;
        }
    }

    static List<Node> sections(ServerSubLevel station) {
        return sections(Flight.group(station));
    }

    private static List<Node> sections(List<ServerSubLevel> parts) {
        var result = new ArrayList<Node>();
        for (var part : parts) {
            var pose = new Pose3d(part.logicalPose());
            for (var holder : part.getPlot().getLoadedChunks()) {
                var chunk = holder.getChunk();
                for (int i = 0; i < chunk.getSectionsCount(); i++) {
                    var section = chunk.getSection(i);
                    if (!section.hasOnlyAir()) {
                        result.add(new Node(part, section, pose, chunk.getPos().getMinBlockX(),
                            chunk.getSectionYFromSectionIndex(i) * 16, chunk.getPos().getMinBlockZ(), 16,
                            !section.maybeHas(BlockState::isAir)));
                    }
                }
            }
        }
        return result;
    }

    // A search snapshot is used within one server operation, while poses and blocks are stable.
    static final class Hull {
        private final Region root;

        private Hull(List<Node> sections) {
            var ordered = new ArrayList<>(sections);
            root = ordered.isEmpty() ? null : combine(ordered);
        }
    }

    static Hull hull(List<ServerSubLevel> parts) {
        return new Hull(sections(parts));
    }

    private static Region combine(List<Node> sections) {
        var first = sections.getFirst();
        if (sections.size() == 1) return first;
        AABB bounds = first.box();
        for (int index = 1; index < sections.size(); index++) bounds = bounds.minmax(sections.get(index).box());
        double width = bounds.getXsize(), height = bounds.getYsize(), depth = bounds.getZsize();
        if (width >= height && width >= depth) sections.sort(Comparator.comparingDouble(node -> node.box.minX + node.box.maxX));
        else if (height >= depth) sections.sort(Comparator.comparingDouble(node -> node.box.minY + node.box.maxY));
        else sections.sort(Comparator.comparingDouble(node -> node.box.minZ + node.box.maxZ));
        int middle = sections.size() / 2;
        var left = combine(sections.subList(0, middle));
        var right = combine(sections.subList(middle, sections.size()));
        return new SectionBranch(bounds, left.size() + right.size(), List.of(left, right));
    }

    private record Pair(Region host, Region guest, double x, double y, double z, double distanceSquared) {}

    private static final Comparator<Pair> NEAREST_FIRST = Comparator.comparingDouble(Pair::distanceSquared)
        .thenComparingInt(pair -> pair.host().size() + pair.guest().size());

    static Vec3 closest(List<Node> host, List<Node> guest, Vec3 offset, double radius) {
        return closest(new Hull(host), new Hull(guest), offset, radius);
    }

    // The returned vector is directed from host to guest. A null result means the
    // closest occupied blocks are beyond the supplied search radius.
    static Vec3 closest(Hull host, Hull guest, Vec3 offset, double radius) {
        var pair = closestPair(host, guest, offset, radius);
        return pair == null ? null : new Vec3(pair.x(), pair.y(), pair.z());
    }

    record BlockPair(ServerSubLevel host, net.minecraft.core.BlockPos hostBlock,
                     ServerSubLevel guest, net.minecraft.core.BlockPos guestBlock) {}

    static BlockPair closeBlocks(Hull host, Hull guest, double radius) {
        var pair = closestPair(host, guest, Vec3.ZERO, radius);
        if (pair == null) return null;
        var a = (Node)pair.host();
        var b = (Node)pair.guest();
        return new BlockPair(a.part(), new net.minecraft.core.BlockPos(a.x(), a.y(), a.z()),
            b.part(), new net.minecraft.core.BlockPos(b.x(), b.y(), b.z()));
    }

    private static Pair closestPair(Hull host, Hull guest, Vec3 offset, double radius) {
        if (host.root == null || guest.root == null) return null;
        var queue = new PriorityQueue<>(NEAREST_FIRST);
        double limit = radius * radius;
        enqueue(queue, host.root, guest.root, offset, limit);
        while (!queue.isEmpty()) {
            var pair = queue.remove();
            var a = pair.host();
            var b = pair.guest();
            if (a.size() == 1 && b.size() == 1) return pair;
            if (a.size() >= b.size()) {
                for (var child : a.children()) enqueue(queue, child, b, offset, limit);
            } else {
                for (var child : b.children()) enqueue(queue, a, child, offset, limit);
            }
        }
        return null;
    }

    private static void enqueue(PriorityQueue<Pair> queue, Region host, Region guest, Vec3 offset, double limit) {
        var a = host.box();
        var b = guest.box();
        double x = delta(a.minX, a.maxX, b.minX + offset.x, b.maxX + offset.x);
        double y = delta(a.minY, a.maxY, b.minY + offset.y, b.maxY + offset.y);
        double z = delta(a.minZ, a.maxZ, b.minZ + offset.z, b.maxZ + offset.z);
        double distanceSquared = x * x + y * y + z * z;
        if (distanceSquared <= limit) queue.add(new Pair(host, guest, x, y, z, distanceSquared));
    }

    static double distanceSquared(AABB a, AABB b) {
        double x = delta(a.minX, a.maxX, b.minX, b.maxX);
        double y = delta(a.minY, a.maxY, b.minY, b.maxY);
        double z = delta(a.minZ, a.maxZ, b.minZ, b.maxZ);
        return x*x + y*y + z*z;
    }

    private static double delta(double aMin, double aMax, double bMin, double bMax) {
        return bMin > aMax ? bMin - aMax : bMax < aMin ? bMax - aMin : 0;
    }
}

