package space.sableorbit.mixin;

import dev.ryanhcode.sable.Sable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.*;

@Mixin(Player.class)
abstract class EvaPlayerMixin {
    @Unique private Vec3 sableorbits$deckVelocity;
    @Unique private Level sableorbits$lastLevel;
    @Inject(method="travel",at=@At("HEAD"))
    private void inheritDeckVelocity(Vec3 input, CallbackInfo ci) {
        var p=(Player)(Object)this;
        if (p.level()!=sableorbits$lastLevel) sableorbits$deckVelocity=null;
        sableorbits$lastLevel=p.level();
        if (!OrbitSpaces.isolated(p.level()) || p.isSpectator() || !p.isControlledByLocalInstance()) return;
        var deck=Sable.HELPER.getTrackingOrVehicleSubLevel(p);
        if (deck!=null) {
            var local=deck.logicalPose().transformPositionInverse(p.position());
            sableorbits$deckVelocity=deck.logicalPose().transformPosition(local).subtract(deck.lastPose().transformPosition(local));
        } else if (sableorbits$deckVelocity!=null) {
            if (Spacewalk.drifting(p)) p.setDeltaMovement(p.getDeltaMovement().add(sableorbits$deckVelocity));
            sableorbits$deckVelocity=null;
        }
    }
    @ModifyConstant(method="travel",constant=@Constant(doubleValue=0.6))
    private double verticalFlightDrag(double multiplier) {
        return Spacewalk.drifting((Player)(Object)this) ? 1 : multiplier;
    }
}
