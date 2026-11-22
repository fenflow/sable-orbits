package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.GravityFrame;

@Mixin(PathNavigation.class)
abstract class DeckNavigationMixin {
    @Shadow @Final protected Mob mob;
    @Shadow protected Path path;

    @Unique private boolean sableorbits$plotPath(GravityFrame frame) {
        if (frame.deck() == null || path == null || path.isDone()) return false;
        var node = path.getNextNode();
        return frame.deck().getPlot().contains(node.x, node.z);
    }

    // Check arrival against the unrounded plot node. Rounding its world projection first
    // can put the goal on the other side of a wall or leave a mob circling a reached node.
    @ModifyExpressionValue(method="followThePath", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/pathfinder/Path;getNextNodePos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos plotWaypoint(BlockPos original, @Share("feet") LocalRef<Vec3> feet) {
        var frame = GravityFrame.resolve(mob);
        if (!sableorbits$plotPath(frame)) return original;
        feet.set(frame.deck().logicalPose().transformPositionInverse(mob.position()));
        return path.getNextNode().asBlockPos();
    }

    @ModifyExpressionValue(method="followThePath", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;getX()D"))
    private double waypointX(double world, @Share("feet") LocalRef<Vec3> feet) { return feet.get() == null ? world : feet.get().x; }

    @ModifyExpressionValue(method="followThePath", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;getY()D"))
    private double waypointY(double world, @Share("feet") LocalRef<Vec3> feet) { return feet.get() == null ? world : feet.get().y; }

    @ModifyExpressionValue(method="followThePath", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;getZ()D"))
    private double waypointZ(double world, @Share("feet") LocalRef<Vec3> feet) { return feet.get() == null ? world : feet.get().z; }

    @Inject(method="getGroundY", at=@At("HEAD"), cancellable=true)
    private void deckGroundY(Vec3 world, CallbackInfoReturnable<Double> cir) {
        if (sableorbits$plotPath(GravityFrame.resolve(mob))) cir.setReturnValue(world.y);
    }
}
