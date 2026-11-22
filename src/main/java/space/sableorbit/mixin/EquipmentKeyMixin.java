package space.sableorbit.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.golem.core.client.KeyMappings;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value=KeyMappings.class, remap=false)
abstract class EquipmentKeyMixin {
    @ModifyVariable(method="register(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)Ldev/golem/core/client/KeyMappings$Mapping;",
        at=@At("HEAD"), argsOnly=true)
    private static int equipmentDefault(int original, @Local(argsOnly=true, ordinal=1) String name) {
        return switch (name) {
            case "key.hbm.toggleMagnet" -> GLFW.GLFW_KEY_X;
            case "key.hbm.toggleBack" -> GLFW.GLFW_KEY_G;
            default -> original;
        };
    }
}
