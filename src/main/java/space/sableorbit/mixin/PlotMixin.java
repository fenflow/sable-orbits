package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.BitSet;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
@Mixin(value=SubLevelContainer.class, remap=false)
abstract class PlotMixin {
    @Redirect(method="getFirstEmptyPlot", at=@At(value="INVOKE",target="Ljava/util/BitSet;get(I)Z"))
    private boolean reserved(BitSet bits, int index) {
        return bits.get(index) || (((SubLevelContainer)(Object)this).getLevel() instanceof ServerLevel level && Hooks.occupiedElsewhere(level,index));
    }
}
