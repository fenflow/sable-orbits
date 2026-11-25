package space.sableorbit.mixin;

import dev.golem.core.data.CompatBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.StationThrusters;

@Mixin(value = CompatBlockEntity.class, remap = false)
abstract class StationPropulsionSyncMixin {
    @Unique private static final String SABLEORBITS_WRONG_DIRECTION = "sableorbits:wrong_direction";

    // Sable plot chunks and later block-entity packets both use this update tag.
    @Inject(method = "getUpdateTag", at = @At("RETURN"))
    private void writeStationHeading(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> result) {
        if (this instanceof StationThrusters.Alignment alignment && alignment.sableorbits$wrongDirection())
            result.getReturnValue().putBoolean(SABLEORBITS_WRONG_DIRECTION, true);
    }

    @Inject(method = "loadAdditional", at = @At("RETURN"))
    private void readStationHeading(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (this instanceof StationThrusters.Alignment alignment)
            alignment.sableorbits$wrongDirection(tag.getBoolean(SABLEORBITS_WRONG_DIRECTION));
    }
}
