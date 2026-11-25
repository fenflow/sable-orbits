package space.sableorbit;

import com.hbm.items.weapon.ItemCustomMissilePart.PartType;
import com.hbm.lib.ForgeDirection;
import com.mojang.blaze3d.vertex.PoseStack;
import com.ntmspace.rocket.RocketPart;
import com.ntmspace.rocket.RocketParts;
import dev.golem.core.client.Frames;
import dev.golem.core.render.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import space.sableorbit.mixin.PlacementOutlineAccess;

final class RocketPlacementOutline {
    private static final double[][] NO_EXTRAS = {};
    private static final int[][] NO_PORT_MARKERS = {};

    private static RocketPart held(Minecraft client) {
        if (client.player == null) return null;
        var part = RocketParts.get(client.player.getMainHandItem());
        if (part == null) return null;
        return part.type() == PartType.THRUSTER || Fuselages.isLiquidFuselage(part) || Fuselages.isSolidFuselage(part) ? part : null;
    }

    static void init() {
        WorldEffects.onBegin(RocketPlacementOutline::submit);
        BlockOutlines.suppressWhen(() -> wheelHand(Minecraft.getInstance()) != null || held(Minecraft.getInstance()) != null);
    }

    private static InteractionHand wheelHand(Minecraft client) {
        if (client.player == null) return null;
        if (ReactionWheelBlock.wideWheel(client.player.getMainHandItem()) != null) return InteractionHand.MAIN_HAND;
        if (held(client) == null && ReactionWheelBlock.wideWheel(client.player.getOffhandItem()) != null) return InteractionHand.OFF_HAND;
        return null;
    }

    private static void submit(CameraInfo camera) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui || client.level == null || client.getCameraEntity() != client.player
            || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        var wheelHand = wheelHand(client);
        RocketPlacement placement;
        if (wheelHand != null) {
            var wheel = ReactionWheelBlock.wideWheel(client.player.getItemInHand(wheelHand));
            placement = RocketPlacement.wheel(wheel, new UseOnContext(client.player, wheelHand, hit));
        } else {
            var part = held(client);
            if (part == null) return;
            placement = RocketPlacement.of(part, new UseOnContext(client.player, InteractionHand.MAIN_HAND, hit));
        }
        var anchor = hit.getBlockPos();
        var pose = new PoseStack();
        SubLevelPoses.around(pose, client.level, anchor, camera.pos, Frames.partialTick());
        int pulse = (int)(255 * (0.75 + 0.25 * Math.sin((System.currentTimeMillis() % (1000 * Math.PI)) / 250)));
        int color = 0xff000000 | pulse << (placement.placeable() ? 8 : 16);
        var occupied = new LongOpenHashSet();
        for (var cell : placement.cells()) occupied.add(cell.asLong());
        var forward = ForgeDirection.getOrientation(placement.facing());
        WorldEffects.submit(pose, LineRenderTypes.lines(2), 2, (matrix, lines) ->
            PlacementOutlineAccess.drawCells(placement.cells(), occupied, anchor.getX(), anchor.getY(), anchor.getZ(), color,
                NO_EXTRAS, 0, forward, forward.getRotation(ForgeDirection.UP), 0, 0, 0, NO_PORT_MARKERS, 0, 0, 0, 0, matrix, lines));
    }
}
