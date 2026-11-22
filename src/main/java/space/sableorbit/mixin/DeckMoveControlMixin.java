package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import space.sableorbit.Attitude;
import space.sableorbit.GravityFrame;

@Mixin(MoveControl.class)
abstract class DeckMoveControlMixin {
    @Shadow @Final protected Mob mob;
    @Shadow protected double wantedX, wantedY, wantedZ;
    @Shadow public abstract boolean hasWanted();
    @Unique private Vec3 sableorbits$plotFeet;

    @WrapMethod(method="tick")
    private void steerOnDeck(Operation<Void> tick) {
        if (!hasWanted()) { tick.call(); return; }
        var frame = GravityFrame.resolve(mob);
        if (frame.deck() == null) { tick.call(); return; }
        // Navigation gives the world projection of its plot waypoint. Vanilla's steering and
        // step-height checks operate on a horizontal plane, so supply its deck-relative delta.
        double x = wantedX, y = wantedY, z = wantedZ;
        var delta = Attitude.deckBasis(frame).transformInverse(new Vector3d(x - mob.getX(), y - mob.getY(), z - mob.getZ()));
        sableorbits$plotFeet = frame.deck().logicalPose().transformPositionInverse(mob.position());
        try {
            wantedX = mob.getX() + delta.x;
            wantedY = mob.getY() + delta.y;
            wantedZ = mob.getZ() + delta.z;
            tick.call();
        } finally {
            wantedX = x;
            wantedY = y;
            wantedZ = z;
            sableorbits$plotFeet = null;
        }
    }

    @ModifyExpressionValue(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;blockPosition()Lnet/minecraft/core/BlockPos;"))
    private BlockPos stepBlock(BlockPos world) {
        return sableorbits$plotFeet == null ? world : BlockPos.containing(sableorbits$plotFeet);
    }

    @ModifyExpressionValue(method="tick", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/Mob;getY()D", ordinal=1))
    private double stepHeight(double worldY) {
        return sableorbits$plotFeet == null ? worldY : sableorbits$plotFeet.y;
    }
}
