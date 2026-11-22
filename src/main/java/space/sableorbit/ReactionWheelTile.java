package space.sableorbit;

import com.hbm.api.energymk2.IEnergyReceiverMK2;
import com.hbm.lib.ForgeDirection;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

final class ReactionWheelTile extends BlockEntity implements IEnergyReceiverMK2 {
    private static final long ACTIVE_HE_PER_TICK = 80;
    private static final double MAX_TURN_RATE = Math.PI / 4; // radians per second
    private static final double MAX_ANGULAR_IMPULSE = 80; // per wheel per game tick
    private static final Direction[] FACES = Direction.values();
    private long energy;
    private long lastStackTick = Long.MIN_VALUE;

    ReactionWheelTile(BlockPos pos, BlockState state) { super(Engines.WHEEL_TILE.get(), pos, state); }
    private static final long CAPACITY = 8000;
    @Override public long getPower() {
        long charge = 0;
        for (var wheel : stack()) charge += wheel.energy;
        return charge;
    }
    @Override public long getMaxPower() { return CAPACITY * stack().size(); }
    @Override public void setPower(long value) {
        var wheels = stack();
        distribute(wheels, Math.clamp(value, 0L, CAPACITY * wheels.size()));
    }

    private static void distribute(List<ReactionWheelTile> wheels, long charge) {
        long share = charge / wheels.size();
        long remainder = charge % wheels.size();
        for (int i = 0; i < wheels.size(); i++) {
            var wheel = wheels.get(i);
            long next = share + (i < remainder ? 1 : 0);
            if (wheel.energy != next) {
                wheel.energy = next;
                wheel.setChanged();
            }
        }
    }
    @Override public boolean isLoaded() { return level != null && !isRemoved(); }
    @Override public boolean canConnect(ForgeDirection side) {
        return side != null && side != ForgeDirection.UNKNOWN && side.toDirection().getAxis() == getBlockState().getValue(ReactionWheelBlock.AXIS);
    }

    private ReactionWheelTile neighbor(Direction side, Direction.Axis axis) {
        var pos = worldPosition.relative(side);
        if (!level.hasChunkAt(pos)) return null;
        return level.getBlockEntity(pos) instanceof ReactionWheelTile wheel && wheel.getBlockState().getValue(ReactionWheelBlock.AXIS) == axis ? wheel : null;
    }

    private List<ReactionWheelTile> stack() {
        if (level == null || isRemoved()) return List.of(this);
        var axis = getBlockState().getValue(ReactionWheelBlock.AXIS);
        var end = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        var first = this;
        ReactionWheelTile previous;
        while ((previous = first.neighbor(end.getOpposite(), axis)) != null) first = previous;
        var next = first.neighbor(end, axis);
        if (next == null) return List.of(first);
        var wheels = new ArrayList<ReactionWheelTile>();
        wheels.add(first);
        for (var wheel = next; wheel != null; wheel = wheel.neighbor(end, axis)) wheels.add(wheel);
        return wheels;
    }

    void tick() {
        long now = level.getGameTime();
        if (lastStackTick == now) return;
        var wheels = stack();
        var end = Direction.get(Direction.AxisDirection.POSITIVE, getBlockState().getValue(ReactionWheelBlock.AXIS));
        long charge = 0;
        for (var wheel : wheels) {
            wheel.lastStackTick = now;
            charge += wheel.energy;
        }
        if (charge == 0) return;
        if (charge >= ACTIVE_HE_PER_TICK && !OrbitSpaces.unoccupied(level) && wheels.getFirst().signal(end.getOpposite()) == 0 && wheels.getLast().signal(end) == 0
                && Sable.HELPER.getContaining(level, worldPosition) instanceof ServerSubLevel ship) {
            int motors = (int)Math.min(wheels.size(), charge / ACTIVE_HE_PER_TICK);
            if (applyTorque(ship, wheels, end, motors)) charge -= motors * ACTIVE_HE_PER_TICK;
        }
        distribute(wheels, charge);
    }

    private boolean applyTorque(ServerSubLevel ship, List<ReactionWheelTile> wheels, Direction end, int motors) {
        var body = RigidBodyHandle.of(ship);
        var mass = ship.getMassTracker();
        if (body == null || !body.isValid() || mass == null || mass.isInvalid()) return false;
        var command = new Vector3d();
        for (var side : FACES) {
            if (side.getAxis() == end.getAxis()) continue;
            int strength = 0;
            for (var wheel : wheels) strength = Math.max(strength, wheel.signal(side));
            command.add(side.getStepX()*strength, side.getStepY()*strength, side.getStepZ()*strength);
        }
        var target = new Vector3d(end.getStepX(), end.getStepY(), end.getStepZ()).cross(command);
        target.mul(MAX_TURN_RATE / Math.max(15, target.length()));
        var localSpin = body.getAngularVelocity(new Vector3d());
        ship.logicalPose().orientation().transformInverse(localSpin);
        var correction = target.sub(localSpin);
        if (correction.lengthSquared() < 1.0e-8) return false;

        var impulse = mass.getInertiaTensor().transform(correction).mul(Math.min(1, 0.15 * motors));
        double magnitude = impulse.length();
        if (!Double.isFinite(magnitude) || magnitude == 0) return false;
        double limit = MAX_ANGULAR_IMPULSE * motors;
        if (magnitude > limit) impulse.mul(limit / magnitude);
        body.applyAngularImpulse(impulse);
        return true;
    }
    private int signal(Direction side) {
        return level.getSignal(worldPosition.relative(side), side);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putLong("energy", energy);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        energy = Math.clamp(tag.getLong("energy"), 0L, CAPACITY);
    }
}
