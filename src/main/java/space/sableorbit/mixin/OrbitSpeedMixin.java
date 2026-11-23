package space.sableorbit.mixin;

import com.ntmspace.orbit.Orbit;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
abstract class OrbitSpeedMixin {
    @Unique private static final double sableorbits$maxOrbitalSpeed = 350;
    @Unique private static final Vector3d sableorbits$noAngularChange = new Vector3d();
    @Unique private final Vector3d sableorbits$linearVelocity = new Vector3d();

    // Limit queued thrust before integration and collision response before Sable samples the new pose.
    @Inject(method = "tickPipelinePhysics", at = {
        @At(value = "FIELD", target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;IN_PHYSICS_STEP:Z",
            opcode = org.objectweb.asm.Opcodes.PUTSTATIC, ordinal = 0),
        @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;updateAllPoses(Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;)V")
    }, require = 2)
    private void limitOrbitalSpeed(ServerSubLevelContainer container, CallbackInfo ci) {
        if (!Orbit.inOrbit(container.getLevel())) return;
        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        for (var sublevel : container.getAllSubLevels()) {
            if (sublevel.isRemoved()) continue;
            var velocity = pipeline.getLinearVelocity(sublevel, sableorbits$linearVelocity);
            double speedSquared = velocity.lengthSquared();
            if (speedSquared > sableorbits$maxOrbitalSpeed * sableorbits$maxOrbitalSpeed) {
                velocity.mul(sableorbits$maxOrbitalSpeed / Math.sqrt(speedSquared) - 1);
                pipeline.addLinearAndAngularVelocity(sublevel, velocity, sableorbits$noAngularChange);
            }
        }
    }
}
