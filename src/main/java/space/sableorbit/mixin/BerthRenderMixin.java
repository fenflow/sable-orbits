package space.sableorbit.mixin;

import com.ntmspace.client.render.rocket.*;
import com.ntmspace.entity.rocket.EntityRideableRocket;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.golem.core.render.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.Berth;

@Mixin(value=RenderRocketCustom.class,remap=false)
abstract class BerthRenderMixin {
    @Unique private EntityRideableRocket sableorbits$rocket;
    @Inject(method="extract(Lcom/ntmspace/entity/rocket/EntityRideableRocket;Lcom/ntmspace/client/render/rocket/RocketRenderState;F)V",at=@At("TAIL"))
    private void capture(EntityRideableRocket r,RocketRenderState state,float partial,CallbackInfo ci) { sableorbits$rocket=r; }
    @Inject(method="submit(Lcom/ntmspace/client/render/rocket/RocketRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Ldev/golem/core/render/SubmitSink;Ldev/golem/core/render/CameraInfo;)V",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",shift=At.Shift.AFTER))
    private void orient(RocketRenderState state,PoseStack stack,SubmitSink sink,CameraInfo camera,CallbackInfo ci) {
        var r=sableorbits$rocket;
        if(r==null)return;
        var pos=r.getEntityData().get(Berth.PORT);
        if(pos.equals(BlockPos.ZERO)) {
            var rider=net.minecraft.client.Minecraft.getInstance().player;
            if(rider!=null && rider.getVehicle()==r) {
                var seat=EntityRideableRocket.direction(r.getPitch(state.interp),r.getYRot(),r.getMountedYOffset()+1.12).add(0,-1.62,0);
                var correction=rider.getPosition(state.interp).subtract(seat).subtract(r.getPosition(state.interp));
                stack.translate(correction.x,correction.y,correction.z);
            }
            return;
        }
        if( !(Sable.HELPER.getContaining(r.level(),pos) instanceof ClientSubLevel ship)) return;
        var pose=ship.renderPose(state.interp);
        float gap=Berth.renderGap(r,state.interp);
        var offset=pose.transformPosition(Berth.base(r,pos,gap)).subtract(r.getPosition(state.interp));
        stack.translate(offset.x,offset.y,offset.z);
        stack.mulPose(new org.joml.Quaternionf(pose.orientation()));
    }
}


