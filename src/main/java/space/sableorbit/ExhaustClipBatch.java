package space.sableorbit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.util.iterator.ListBackedFilterIterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class ExhaustClipBatch implements AutoCloseable {
    private static final ThreadLocal<ExhaustClipBatch> ACTIVE = new ThreadLocal<>();

    private final ServerLevel level;
    private final BoundingBox3dc bounds;
    private final List<SubLevel> candidates;
    private final ExhaustClipBatch previous;

    ExhaustClipBatch(ServerLevel level, BoundingBox3dc bounds) {
        this.level = level;
        this.bounds = bounds;
        List<SubLevel> nearby = List.of();
        for (var plot : Sable.HELPER.getAllIntersecting(level, bounds)) {
            if (nearby.isEmpty()) nearby = new ArrayList<>();
            nearby.add(plot);
        }
        candidates = nearby;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    boolean isEmpty() { return candidates.isEmpty(); }

    public static Iterable<SubLevel> intersecting(Level level, BoundingBox3dc rayBounds) {
        var batch = ACTIVE.get();
        if (batch == null || batch.level != level
                || !batch.bounds.contains(rayBounds.minX(), rayBounds.minY(), rayBounds.minZ())
                || !batch.bounds.contains(rayBounds.maxX(), rayBounds.maxY(), rayBounds.maxZ())) return null;
        if (batch.candidates.isEmpty()) return batch.candidates;
        return () -> new ListBackedFilterIterator<>(plot -> plot.boundingBox().intersects(rayBounds), batch.candidates);
    }

    @Override public void close() { ACTIVE.set(previous); }
}
