package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
@Mixin(value=com.ntmspace.block.station.BlockOrbitalStationComputer.class, remap=false)
abstract class ComputerMixin {
    @Inject(method="useAtCore", at=@At("HEAD"), cancellable=true)
    private void open(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (player instanceof ServerPlayer sp && Hooks.computer(sp,pos)) cir.setReturnValue(InteractionResult.CONSUME);
    }
}
