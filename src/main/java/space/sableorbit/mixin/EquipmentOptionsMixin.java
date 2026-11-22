package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Options;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
abstract class EquipmentOptionsMixin {
    @Unique private static final String sableorbits$keyVersion = "sableorbitsEquipmentKeys";

    @ModifyExpressionValue(method="load(Z)V", at=@At(value="INVOKE",
        target="Lnet/minecraft/client/Options;dataFix(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag migrateEquipment(CompoundTag options) {
        if (!options.contains(sableorbits$keyVersion)) {
            if ("key.keyboard.z".equals(options.getString("key_key.hbm.toggleMagnet")))
                options.putString("key_key.hbm.toggleMagnet", "key.keyboard.x");
            if ("key.keyboard.c".equals(options.getString("key_key.hbm.toggleBack")))
                options.putString("key_key.hbm.toggleBack", "key.keyboard.g");
        }
        return options;
    }

    @Inject(method="processOptionsForge", at=@At("RETURN"))
    private void saveEquipmentKeyVersion(Options.FieldAccess fields, CallbackInfo ci) {
        fields.process(sableorbits$keyVersion, 1);
    }
}
