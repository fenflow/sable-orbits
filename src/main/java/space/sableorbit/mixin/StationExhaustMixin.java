package space.sableorbit.mixin;

import com.hbm.particle.RocketFlameColor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.ntmspace.tileentity.station.TileEntityMachineHTR3;
import com.ntmspace.tileentity.station.TileEntityMachineHTRF4;
import com.ntmspace.tileentity.station.TileEntityMachineLPW2Space;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import space.sableorbit.OrbitalParticles;

@Mixin(value = {TileEntityMachineHTR3.class, TileEntityMachineHTRF4.class, TileEntityMachineLPW2Space.class}, remap = false)
abstract class StationExhaustMixin {
    @Group(name = "stationExhaust", min = 1, max = 1)
    @WrapOperation(method = "clientTick", at = @At(value = "INVOKE",
        target = "Lcom/hbm/client/ClientEffects;spawnRocketFlame(Lnet/minecraft/world/level/Level;DDDFDDDILcom/hbm/particle/RocketFlameColor;)V"), require = 0)
    private static void colored(Level level, double x, double y, double z, float size, double vx, double vy, double vz,
                                int age, RocketFlameColor color, Operation<Void> spawn, @Local(argsOnly = true) BlockPos enginePos) {
        OrbitalParticles.emitStationExhaust(level, enginePos, new Vec3(x, y, z), new Vec3(vx, vy, vz), (origin, velocity) ->
            spawn.call(level, origin.x, origin.y, origin.z, size, velocity.x, velocity.y, velocity.z, age, color));
    }

    @Group(name = "stationExhaust", min = 1, max = 1)
    @WrapOperation(method = "clientTick", at = @At(value = "INVOKE",
        target = "Lcom/hbm/client/ClientEffects;spawnRocketFlame(Lnet/minecraft/world/level/Level;DDDFDDDI)V"), require = 0)
    private static void uncolored(Level level, double x, double y, double z, float size, double vx, double vy, double vz,
                                  int age, Operation<Void> spawn, @Local(argsOnly = true) BlockPos enginePos) {
        OrbitalParticles.emitStationExhaust(level, enginePos, new Vec3(x, y, z), new Vec3(vx, vy, vz), (origin, velocity) ->
            spawn.call(level, origin.x, origin.y, origin.z, size, velocity.x, velocity.y, velocity.z, age));
    }
}
