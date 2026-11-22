package space.sableorbit;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record FlightVelocity(int id,Vec3 velocity) implements CustomPacketPayload {
    private static final Type<FlightVelocity> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("sableorbits","flight_velocity"));
    public Type<FlightVelocity> type() { return TYPE; }

    public static boolean needed(Entity entity) {
        if (!entity.hasData(LaunchMotion.DRIFT) || !com.ntmspace.orbit.Orbit.inOrbit(entity.level())) return false;
        var v=entity.getDeltaMovement();
        return Math.max(Math.abs(v.x),Math.max(Math.abs(v.y),Math.abs(v.z)))>3.9;
    }

    static void register(RegisterPayloadHandlersEvent event) {
        StreamCodec<RegistryFriendlyByteBuf,FlightVelocity> codec=StreamCodec.of(
            (buf,p)->{ buf.writeVarInt(p.id); buf.writeVec3(p.velocity); },
            buf->new FlightVelocity(buf.readVarInt(),buf.readVec3()));
        event.registrar("1").playToClient(TYPE,codec,(p,ctx)->{
            var entity=ctx.player().level().getEntity(p.id);
            if (entity!=null && LaunchMotion.ballistic(entity)) entity.setDeltaMovement(p.velocity);
        });
    }
}
