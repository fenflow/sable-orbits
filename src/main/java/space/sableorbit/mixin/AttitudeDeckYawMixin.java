package space.sableorbit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.GravityFrame;

@Mixin(GameRenderer.class)
abstract class AttitudeDeckYawMixin {
    @Unique private LocalPlayer sableorbits$trackedPilot;
    @Unique private float sableorbits$entryYaw;

    @WrapMethod(method="renderLevel")
    private void captureHeading(DeltaTracker clock, Operation<Void> render) {
        var pilot = Minecraft.getInstance().player;
        if (pilot != null && !pilot.isPassenger() && Sable.HELPER.getTrackingSubLevel(pilot) != null) {
            var frame = GravityFrame.resolve(pilot);
            if (frame.free() || frame.deck() != null) {
                sableorbits$trackedPilot = pilot;
                sableorbits$entryYaw = pilot.getYRot();
            }
        }
        try {
            render.call(clock);
        } finally {
            sableorbits$trackedPilot = null;
        }
    }

    // Sable adds the tracked hull's world-Y twist at renderLevel HEAD. Our view
    // follows its full plot rotation on a deck and holds inertial axes in vacuum.
    @Inject(method="renderLevel", at=@At(value="INVOKE", target="Lnet/minecraft/client/DeltaTracker;getGameTimeDeltaPartialTick(Z)F", ordinal=0))
    private void retainQuaternionHeading(DeltaTracker clock, CallbackInfo ci) {
        var pilot = sableorbits$trackedPilot;
        if (pilot == null || pilot.getYRot() == sableorbits$entryYaw) return;
        float delta = sableorbits$entryYaw - pilot.getYRot();
        pilot.setYRot(sableorbits$entryYaw);
        pilot.yRotO += delta;
        pilot.yHeadRot += delta;
        pilot.yHeadRotO += delta;
        pilot.yBodyRot += delta;
        pilot.yBodyRotO += delta;
    }
}
