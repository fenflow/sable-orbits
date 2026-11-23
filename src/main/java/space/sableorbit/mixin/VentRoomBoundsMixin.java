package space.sableorbit.mixin;

import com.ntmspace.atmosphere.AtmosphereBlob;
import com.ntmspace.atmosphere.AtmosphereProvider;
import com.ntmspace.tileentity.TileEntityAirPump;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.GravityFrame;

@Mixin(value = AtmosphereBlob.class, remap = false)
abstract class VentRoomBoundsMixin implements GravityFrame.RoomBounds {
    @Shadow @Final protected AtmosphereProvider handler;
    @Unique private BoundingBox3i sableorbits$bounds;

    // Index native room growth. Removed cells may leave a larger box until the next
    // rebuild, so the live graph still owns membership and pressure checks.
    @Inject(method = "addSingleBlock", at = @At("HEAD"))
    private void growRoomBounds(BlockPos cell, CallbackInfo ci) {
        if (!(handler instanceof TileEntityAirPump)) return;
        if (sableorbits$bounds == null) sableorbits$bounds = new BoundingBox3i(cell, cell);
        else sableorbits$bounds.expandTo(cell.getX(), cell.getY(), cell.getZ());
    }

    @Inject(method = "clearBlob", at = @At("RETURN"))
    private void clearRoomBounds(CallbackInfo ci) {
        sableorbits$bounds = null;
    }

    @Override public boolean sableorbits$intersectsRoomBounds(BlockPos min, BlockPos max) {
        var bounds = sableorbits$bounds;
        return bounds == null || bounds.intersects(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }
}
