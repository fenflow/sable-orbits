package space.sableorbit.mixin;

import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SubLevelSnapshotInterpolator.class, remap = false)
public interface InterpolatorAccess {
    @Accessor("stopped") boolean sableorbits$stopped();
}
