package space.sableorbit;

import com.hbm.api.energymk2.IEnergyReceiverMK2;
import com.hbm.lib.ForgeDirection;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class ReactionWheelTile extends BlockEntity implements IEnergyReceiverMK2 {
    private static final long CAPACITY = 8000;
    private static final long ACTIVE_HE_PER_TICK = 80;
    private static final double MAX_TURN_RATE = Math.PI / 4; // radians per second
    private static final double SMALL_WHEEL_ANGULAR_IMPULSE = 80; // per game tick
    private static final Direction[] FACES = Direction.values();
    private static final Map<ServerLevel, Long> STACK_REVISIONS = new WeakHashMap<>();
    private long energy;
    private long lastStackTick = Long.MIN_VALUE;
    private WheelStack cachedStack;

    private static final class WheelStack {
        final List<ReactionWheelTile> wheels;
        final long tick;
        final long revision;
        long charge;
        boolean distributed;

        WheelStack(List<ReactionWheelTile> wheels, long tick, long revision) {
            this.wheels = wheels;
            this.tick = tick;
            this.revision = revision;
            for (var wheel : wheels) charge += wheel.energy;
        }

        void distribute(long nextCharge) {
            if (distributed && charge == nextCharge) return;
            charge = nextCharge;
            distributed = true;
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
    }

    ReactionWheelTile(BlockPos pos, BlockState state) { super(Engines.WHEEL_TILE.get(), pos, state); }

    ReactionWheelBlock block() { return (ReactionWheelBlock)getBlockState().getBlock(); }
    Direction.Axis axis() { return getBlockState().getValue(ReactionWheelBlock.AXIS); }
    boolean isMaster() { return !block().wide() || getBlockState().getValue(ReactionWheelBlock.SECTION) == 0; }
    BlockPos masterPos() {
        var state = getBlockState();
        return isMaster() ? worldPosition : worldPosition.subtract(ReactionWheelBlock.sectionOffset(state, state.getValue(ReactionWheelBlock.SECTION)));
    }

    BlockPos minimumCorner() {
        var diagonal = ReactionWheelBlock.sectionOffset(getBlockState(), 3);
        return worldPosition.offset(Math.min(diagonal.getX(), 0), Math.min(diagonal.getY(), 0), Math.min(diagonal.getZ(), 0));
    }

    ReactionWheelTile master() {
        if (level == null || isRemoved()) return null;
        if (isMaster()) return this;
        var pos = masterPos();
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof ReactionWheelTile root)) return null;
        var state = getBlockState();
        return !root.isRemoved() && root.getBlockState() == state.setValue(ReactionWheelBlock.SECTION, 0) ? root : null;
    }

    @Override public void onLoad() {
        super.onLoad();
        invalidateStack();
        if (!isMaster() && level instanceof ServerLevel server) server.scheduleTick(worldPosition, block(), 1);
    }

    @Override public void setLevel(Level newLevel) {
        if (level != newLevel) invalidateStack();
        super.setLevel(newLevel);
    }

    @Override public void setBlockState(BlockState state) {
        if (state != getBlockState()) invalidateStack();
        super.setBlockState(state);
    }

    @Override public void setRemoved() {
        if (!isRemoved()) invalidateStack();
        super.setRemoved();
    }

    @Override public void clearRemoved() {
        invalidateStack();
        super.clearRemoved();
    }

    private void invalidateStack() {
        cachedStack = null;
        if (level instanceof ServerLevel server) STACK_REVISIONS.merge(server, 1L, Long::sum);
    }

    void dismantle() {
        for (var cell : block().cells(worldPosition, getBlockState()).reversed()) {
            if (level.hasChunkAt(cell) && level.getBlockEntity(cell) instanceof ReactionWheelTile section
                    && section.master() == this) level.removeBlock(cell, false);
        }
    }

    private WheelStack powerStack() {
        var root = isMaster() ? this : master();
        return root == null ? null : root.stack();
    }

    @Override public long getPower() {
        var stack = powerStack();
        return stack == null ? 0 : stack.charge;
    }
    @Override public long getMaxPower() {
        var stack = powerStack();
        return stack == null ? 0 : CAPACITY * stack.wheels.size();
    }
    @Override public void setPower(long value) {
        var stack = powerStack();
        if (stack != null) stack.distribute(Math.clamp(value, 0L, CAPACITY * stack.wheels.size()));
    }

    @Override public long transferPower(long amount, boolean simulate) {
        var stack = powerStack();
        if (stack == null) return Math.max(0, amount);
        long accepted = Math.min(amount, CAPACITY * stack.wheels.size() - stack.charge);
        if (!simulate) stack.distribute(Math.max(0, stack.charge + accepted));
        return amount - accepted;
    }
    @Override public boolean isLoaded() { return master() != null; }
    @Override public boolean canConnect(ForgeDirection side) {
        return side != null && side != ForgeDirection.UNKNOWN && side.toDirection().getAxis() == axis();
    }

    private ReactionWheelTile neighbor(Direction side, Direction.Axis axis) {
        var pos = worldPosition.relative(side);
        if (!level.hasChunkAt(pos)) return null;
        if (!(level.getBlockEntity(pos) instanceof ReactionWheelTile section) || section.axis() != axis
                || section.block().wide() != block().wide()) return null;
        var wheel = section.master();
        return wheel != null && (!block().wide() || wheel.minimumCorner().equals(minimumCorner().relative(side))) ? wheel : null;
    }

    private WheelStack stack() {
        if (level == null || isRemoved()) return new WheelStack(List.of(this), 0, 0);
        if (!(level instanceof ServerLevel server)) return new WheelStack(collectStack(), 0, 0);
        long tick = server.getGameTime();
        long revision = STACK_REVISIONS.getOrDefault(server, 0L);
        if (cachedStack != null && cachedStack.tick == tick && cachedStack.revision == revision) return cachedStack;

        var stack = new WheelStack(collectStack(), tick, revision);
        for (var wheel : stack.wheels) wheel.cachedStack = stack;
        return stack;
    }

    private List<ReactionWheelTile> collectStack() {
        var axis = axis();
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
        if (block().wide() && now % 20 == 0) {
            for (var cell : block().cells(worldPosition, getBlockState())) {
                if (level.hasChunkAt(cell) && !(level.getBlockEntity(cell) instanceof ReactionWheelTile section && section.master() == this)) {
                    Block.popResource(level, worldPosition, new ItemStack(block()));
                    dismantle();
                    return;
                }
            }
        }
        if (lastStackTick == now) return;
        var stack = stack();
        var wheels = stack.wheels;
        var end = Direction.get(Direction.AxisDirection.POSITIVE, axis());
        long charge = stack.charge;
        for (var wheel : wheels) wheel.lastStackTick = now;
        if (charge == 0) return;
        if (charge >= ACTIVE_HE_PER_TICK && !OrbitSpaces.unoccupied(level) && wheels.getFirst().signal(end.getOpposite()) == 0 && wheels.getLast().signal(end) == 0
                && Sable.HELPER.getContaining(level, worldPosition) instanceof ServerSubLevel ship) {
            int motors = (int)Math.min(wheels.size(), charge / ACTIVE_HE_PER_TICK);
            int motorStrength = 0;
            for (int index = 0; index < motors; index++) motorStrength += wheels.get(index).block().motorStrength();
            if (applyTorque(ship, wheels, end, motorStrength)) charge -= motors * ACTIVE_HE_PER_TICK;
        }
        stack.distribute(charge);
    }

    private boolean applyTorque(ServerSubLevel ship, List<ReactionWheelTile> wheels, Direction end, int motorStrength) {
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

        var impulse = mass.getInertiaTensor().transform(correction).mul(Math.min(1, 0.15 * motorStrength));
        double magnitude = impulse.length();
        if (!Double.isFinite(magnitude) || magnitude == 0) return false;
        double limit = SMALL_WHEEL_ANGULAR_IMPULSE * motorStrength;
        if (magnitude > limit) impulse.mul(limit / magnitude);
        body.applyAngularImpulse(impulse);
        return true;
    }
    private int signal(Direction side) {
        if (!block().wide()) return level.getSignal(worldPosition.relative(side), side);
        var axis = axis();
        var state = getBlockState();
        int diagonal = ReactionWheelBlock.sectionOffset(state, 3).get(side.getAxis());
        int edge = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? Math.max(diagonal, 0) : Math.min(diagonal, 0);
        int strength = 0;
        for (int section = 0; section < 4; section++) {
            var offset = ReactionWheelBlock.sectionOffset(state, section);
            // Rim controls only read neighbors outside the footprint.
            if (side.getAxis() != axis && offset.get(side.getAxis()) != edge) continue;
            strength = Math.max(strength, level.getSignal(worldPosition.offset(offset).relative(side), side));
        }
        return strength;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (isMaster()) tag.putLong("energy", energy);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        long next = isMaster() ? Math.clamp(tag.getLong("energy"), 0L, CAPACITY) : 0;
        if (energy != next) invalidateStack();
        energy = next;
    }
}
