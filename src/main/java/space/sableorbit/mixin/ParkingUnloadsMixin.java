package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.OrbitSpaces;
import java.util.Queue;

@Mixin(ChunkMap.class)
abstract class ParkingUnloadsMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Queue<Runnable> unloadQueue;

    // Vanilla services smaller unload queues only while the server tick has time left.
    // Raise its minimum for an otherwise vacant retiring orbit. Vanilla still runs each callback.
    @ModifyExpressionValue(method="processUnloads", at=@At(value="INVOKE", target="Ljava/lang/Math;max(II)I"), allow=1)
    private int sableorbits$parkingUnloads(int minimum) {
        if (minimum != 0 || unloadQueue.isEmpty()) return minimum;
        return OrbitSpaces.parkingUnloadAllowance(level);
    }
}
