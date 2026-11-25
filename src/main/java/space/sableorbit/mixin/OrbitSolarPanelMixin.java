package space.sableorbit.mixin;

import com.ntmspace.tileentity.machine.TileEntityMachineSolarPanel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.OrbitSpaces;
import space.sableorbit.OrbitSunlight;

@Mixin(value = TileEntityMachineSolarPanel.class, remap = false)
abstract class OrbitSolarPanelMixin {
    @Inject(method = "serverTick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lcom/ntmspace/tileentity/machine/TileEntityMachineSolarPanel;providePower()V"), cancellable = true)
    private static void generateInOrbit(Level level, BlockPos pos, BlockState state, TileEntityMachineSolarPanel panel, CallbackInfo ci) {
        if (level instanceof ServerLevel server && OrbitSpaces.isolated(server)) {
            OrbitSunlight.generate(server, pos, panel);
            ci.cancel();
        }
    }
}
