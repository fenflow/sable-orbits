package space.sableorbit.mixin;
import space.sableorbit.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.ntmspace.orbit.*;
import com.ntmspace.tileentity.station.TileEntityOrbitalStation;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=OrbitalStation.class,remap=false)
public interface EquipmentAccess {
    @Accessor("engines") Set<Propulsion> sableorbits$engines();
    @Accessor("ports") Map<BlockPos,TileEntityOrbitalStation> sableorbits$ports();
    @Accessor("mainPort") void sableorbits$mainPort(TileEntityOrbitalStation port);
}
