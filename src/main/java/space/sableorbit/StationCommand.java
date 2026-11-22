package space.sableorbit;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class StationCommand {
    static void register(RegisterCommandsEvent event) {
        var station = Commands.literal("station");
        station.then(Commands.literal("list").executes(ctx -> {
            var roots=Rendezvous.stations(ctx.getSource().getServer());
            for (var root:roots) {
                var nativeStation=Stations.nativeStation(root);
                if(nativeStation!=null) ctx.getSource().sendSuccess(() -> Component.literal(nativeStation.name+"  "+nativeStation.orbiting.name+"  "+root.getUniqueId()),false);
            }
            return roots.size();
        }));
        station.then(Commands.literal("tp").then(Commands.argument("uuid",StringArgumentType.word())
            .suggests((ctx,b) -> {
                Rendezvous.stations(ctx.getSource().getServer()).forEach(s -> b.suggest(s.getUniqueId().toString()));
                return b.buildFuture();
            }).executes(ctx -> {
                var player=ctx.getSource().getPlayerOrException();
                for (var root:Rendezvous.stations(player.getServer()))
                    if(root.getUniqueId().toString().equals(StringArgumentType.getString(ctx,"uuid")) && board(player,root)) return 1;
                ctx.getSource().sendFailure(Component.literal("Station unavailable or no clear boarding position."));
                return 0;
            })));
        event.getDispatcher().register(Commands.literal("sableorbits").requires(s -> s.hasPermission(2)).then(station));
    }
    static boolean board(ServerPlayer player,ServerSubLevel root) {
        var level=root.getLevel();
        for (var node:HullDistance.sections(root)) {
            for(int y=15;y>=0;y--) for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                var pos=new BlockPos(node.x()+x,node.y()+y,node.z()+z);
                var state=node.section().getBlockState(x,y,z);
                if(state.isAir()) continue;
                var pose=node.part().logicalPose();
                for(var box:state.getCollisionShape(level,pos).toAabbs()) {
                    var shape=box.move(pos);
                    var center=pose.transformPosition(shape.getCenter());
                    var hit=shape.clip(pose.transformPositionInverse(center.add(0,2,0)),pose.transformPositionInverse(center.add(0,-2,0)));
                    if(hit.isEmpty()) continue;
                    var feet=pose.transformPosition(hit.get()).add(0,.015,0);
                    if(!level.noCollision(player,player.getBoundingBox().move(feet.subtract(player.position())))) continue;
                    player.teleportTo(level,feet.x,feet.y,feet.z,player.getYRot(),player.getXRot());
                    player.setDeltaMovement(Vec3.ZERO); player.fallDistance=0;
                    return true;
                }
            }
        }
        // Plants and other collisionless hulls still have an address and can be visited in EVA.
        var bounds=Rendezvous.bounds(root);
        if(bounds!=null) {
            var position=new Vec3(bounds.getCenter().x,bounds.maxY+.1,bounds.getCenter().z);
            if(level.noCollision(player,player.getBoundingBox().move(position.subtract(player.position())))) {
                player.teleportTo(level,position.x,position.y,position.z,player.getYRot(),player.getXRot());
                player.setDeltaMovement(Vec3.ZERO); player.fallDistance=0;
                return true;
            }
        }
        return false;
    }
}
