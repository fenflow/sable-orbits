package space.sableorbit.mixin;

import com.hbm.client.render.MultiblockPlacementHighlight;
import com.hbm.lib.ForgeDirection;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.List;

@Mixin(value = MultiblockPlacementHighlight.class, remap = false)
public interface PlacementOutlineAccess {
    @Invoker("lambda$submit$2")
    static void drawCells(List<BlockPos> cells, LongOpenHashSet occupied, double x, double y, double z, int color,
                          double[][] extras, double coreX, ForgeDirection forward, ForgeDirection transverse,
                          double coreY, double coreZ, int extrasColor, int[][] ports, int portX, int portY, int portZ,
                          int portColor, PoseStack.Pose pose, VertexConsumer lines) { throw new AssertionError(); }
}
