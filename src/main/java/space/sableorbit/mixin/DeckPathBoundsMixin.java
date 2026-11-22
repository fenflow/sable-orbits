package space.sableorbit.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import space.sableorbit.BodyBox;
import space.sableorbit.GravityFrame;

@Mixin(WalkNodeEvaluator.class)
abstract class DeckPathBoundsMixin extends NodeEvaluator {
    // Sable moves the world bounds into the plot before checking clearance. A world
    // envelope of a sideways mob has the wrong height and width in those plot axes.
    @ModifyVariable(method={"getStart", "canReachWithoutCollision"}, at=@At(value="STORE", ordinal=0), ordinal=0)
    private AABB navigationFootprint(AABB bounds) {
        if (!(mob.getBoundingBox() instanceof BodyBox)) return bounds;
        var frame = GravityFrame.resolve(mob);
        if (frame.deck() == null || frame.localUp() != Direction.UP) return bounds;
        var localFeet = frame.deck().logicalPose().transformPositionInverse(mob.position());
        return mob.getDimensions(mob.getPose()).makeBoundingBox(localFeet);
    }
}
