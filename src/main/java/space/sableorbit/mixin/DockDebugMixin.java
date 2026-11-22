package space.sableorbit.mixin;

import com.mojang.blaze3d.vertex.*;
import com.ntmspace.entity.rocket.EntityRideableRocket;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.sableorbit.Berth;

@Mixin(EntityRenderDispatcher.class)
abstract class DockDebugMixin {
    @Inject(method="renderHitbox",at=@At("HEAD"),cancellable=true)
    private static void drawDockedBox(PoseStack stack,VertexConsumer lines,Entity entity,float partial,float red,float green,float blue,CallbackInfo ci) {
        if(!(entity instanceof EntityRideableRocket rocket))return;
        var port=rocket.getEntityData().get(Berth.PORT);
        if(port.equals(BlockPos.ZERO) || !(Sable.HELPER.getContaining(rocket.level(),port) instanceof ClientSubLevel ship))return;
        var pose=ship.renderPose(partial);
        var base=pose.transformPosition(Berth.base(rocket,port,Berth.renderGap(rocket,partial)));
        var shift=base.subtract(rocket.getPosition(partial));
        double w=rocket.getBbWidth()/2;
        stack.pushPose();
        stack.translate(shift.x,shift.y,shift.z);
        stack.mulPose(new org.joml.Quaternionf(pose.orientation()));
        stack.scale((float)pose.scale().x(),(float)pose.scale().y(),(float)pose.scale().z());
        LevelRenderer.renderLineBox(stack,lines,new AABB(-w,0,-w,w,rocket.getBbHeight(),w),red,green,blue,1);
        stack.popPose();
        ci.cancel();
    }
}
