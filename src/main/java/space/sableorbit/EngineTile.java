package space.sableorbit;

import com.hbm.api.fluidmk2.IFluidStandardReceiverMK2;
import com.hbm.inventory.fluid.*;
import com.hbm.inventory.fluid.tank.FluidTankNTM;
import com.hbm.lib.ForgeDirection;
import com.ntmspace.rocket.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

final class EngineTile extends BlockEntity implements IFluidStandardReceiverMK2, IFluidHandler {
    private static final double PHYSICAL_THRUST_SCALE = 0.01;
    private static final int CAPACITY = 16000;
    private RocketPart engine;
    private BlockPos offset = BlockPos.ZERO;
    private FluidTankNTM[] tanks = {};
    private int solid;
    private boolean burning;
    private boolean refreshPortsOnTick;
    private List<BlockPos> cells = List.of();
    EngineTile(BlockPos pos, BlockState state) { super(Engines.TILE.get(),pos,state); }
    void initialize(RocketPart part, BlockPos toCore) {
        engine = part;
        offset = toCore;
        if (part != null && offset.equals(BlockPos.ZERO)) {
            cells = Engines.cells(part,worldPosition,nozzle());
            tanks = java.util.stream.Stream.of(part.getFuel(),part.getOxidizer())
                .filter(f -> f != null && f != Fluids.NONE).map(f -> new FluidTankNTM(f,CAPACITY)).toArray(FluidTankNTM[]::new);
            for (var tank : tanks) tank.bindOwner(this);
        }
        setChanged();
    }
    RocketPart engine() { return engine; }
    boolean isMaster() { return offset.equals(BlockPos.ZERO); }
    boolean isBurning() { return burning; }
    net.minecraft.world.phys.AABB bounds() {
        if (cells.isEmpty()) return new net.minecraft.world.phys.AABB(worldPosition);
        return new net.minecraft.world.phys.AABB(cells.getFirst()).minmax(new net.minecraft.world.phys.AABB(cells.getLast()));
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider p) { var tag=saveWithoutMetadata(p);tag.putBoolean("burning",burning);return tag; }
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    private Direction nozzle() { return getBlockState().getValue(EngineBlock.FACING); }
    boolean inlet(Direction side) {
        if (side == null || engine == null || !isMaster()) return false;
        Direction exhaust = nozzle();
        return side == exhaust.getOpposite() || (tanks.length > 1 && side.getAxis() != exhaust.getAxis());
    }
    @Override public void onLoad() {
        super.onLoad();
        // Ducts in an existing save may still have the old, closed side connection cached.
        refreshPortsOnTick = isMaster() && tanks.length > 1;
    }
    void refreshFluidPorts() {
        refreshPortsOnTick = false;
        level.invalidateCapabilities(worldPosition);
        for (Direction side : Direction.values()) {
            if (inlet(side)) com.hbm.blocks.network.BlockFluidDuct.portChanged(level, worldPosition.relative(side));
        }
    }
    @Override public boolean isLoaded() { return level != null && !isRemoved(); }
    @Override public boolean canConnect(FluidType fluid, ForgeDirection side) {
        return side != null && inlet(side.toDirection()) && Arrays.stream(tanks).anyMatch(t -> t.joins(fluid));
    }
    @Override public boolean exposesFluidContact(FluidType f, ForgeDirection d, boolean output) { return !output && canConnect(f,d); }
    @Override public FluidTankNTM[] getAllTanks() { return tanks; }
    @Override public FluidTankNTM[] getReceivingTanks() { return tanks; }
    @Override public long transferFluid(FluidType fluid, int pressure, long amount) {
        long remaining = IFluidStandardReceiverMK2.super.transferFluid(fluid, pressure, amount);
        if (remaining < amount) setChanged();
        return remaining;
    }
    @Override public int getTanks() { return tanks.length; }
    @Override public FluidStack getFluidInTank(int i) {
        if (tanks[i].getFill() == 0 || tanks[i].getFluid().getFluid() == null) return FluidStack.EMPTY;
        return new FluidStack(tanks[i].getFluid().getFluid(),tanks[i].getFill());
    }
    @Override public int getTankCapacity(int i) { return tanks[i].getMaxFill(); }
    @Override public boolean isFluidValid(int i, FluidStack stack) { return !stack.isEmpty() && tanks[i].accepts(stack.getFluid()); }
    @Override public int fill(FluidStack stack, FluidAction action) {
        for (int i=0; i<tanks.length; i++) if (isFluidValid(i,stack)) {
            int accepted = Math.min(stack.getAmount(),CAPACITY-tanks[i].getFill());
            if (action.execute()) { tanks[i].setFill(tanks[i].getFill()+accepted); setChanged(); }
            return accepted;
        }
        return 0;
    }
    @Override public FluidStack drain(FluidStack f, FluidAction a) { return FluidStack.EMPTY; }
    @Override public FluidStack drain(int amount, FluidAction a) { return FluidStack.EMPTY; }
    boolean loadSolid() {
        if (engine == null || !"SOLID".equals(engine.fuel()) || !offset.equals(BlockPos.ZERO) || solid > CAPACITY-1000) return false;
        solid += 1000;
        setChanged();
        return true;
    }
    void dismantle(boolean drop) {
        if (level == null || engine == null) return;
        if (!offset.equals(BlockPos.ZERO)) {
            if (level.getBlockEntity(worldPosition.offset(offset)) instanceof EngineTile master && master.offset.equals(BlockPos.ZERO))
                master.dismantle(drop);
            return;
        }
        if (drop) Block.popResource(level,worldPosition,new ItemStack(engine.item()));
        for (var cell : cells) if (level.getBlockEntity(cell) instanceof EngineTile piece
                && cell.offset(piece.offset).equals(worldPosition)) level.removeBlock(cell,false);
    }
    void tick() {
        if (level == null || level.isClientSide) return;
        if (refreshPortsOnTick) refreshFluidPorts();
        boolean active=burn();
        if(burning!=active && !isRemoved()) {
            burning=active;
            level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
        }
    }
    private boolean burn() {
        if (OrbitSpaces.unoccupied(level)) return false;
        if (!(level instanceof ServerLevel server) || engine == null) return false;
        if (server.getGameTime()%20 == 0) {
            if (!offset.equals(BlockPos.ZERO)) {
                var master = worldPosition.offset(offset);
                if (level.hasChunkAt(master) && !(level.getBlockEntity(master) instanceof EngineTile)) level.removeBlock(worldPosition,false);
                return false;
            }
            if (cells.stream().anyMatch(p -> level.hasChunkAt(p) && !(level.getBlockEntity(p) instanceof EngineTile))) { dismantle(true); return false; }
        }
        if (OrbitSpaces.unoccupied(level) || !offset.equals(BlockPos.ZERO) || cells.stream().noneMatch(level::hasNeighborSignal)) return false;
        int rate = Math.max(1,(int)Math.ceil(engine.getThrust() / (20.0 * 9.81 * Math.max(1,engine.getISP()))));
        if (tanks.length == 0) {
            if (!"SOLID".equals(engine.fuel()) || solid < rate) return false;
            solid -= rate;
        } else {
            for (var tank : tanks) if (tank.getFill() < rate) return false;
            for (var tank : tanks) tank.setFill(tank.getFill()-rate);
        }
        setChanged();
        var ship=Sable.HELPER.getContaining(level,worldPosition);
        if (ship instanceof ServerSubLevel sub) {
            var handle = RigidBodyHandle.of(sub);
            if (handle != null && handle.isValid()) handle.applyImpulseAtPoint(bounds().getCenter(),
                Vec3.atLowerCornerOf(nozzle().getOpposite().getNormal()).scale(engine.getThrust()*PHYSICAL_THRUST_SCALE/20.0));
        }
        return true;
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag,provider);
        if (engine != null) tag.putString("engine",engine.id());
        tag.putLong("offset",offset.asLong());
        tag.putInt("solid",solid);
        for (int i=0;i<tanks.length;i++) tag.putInt("tank"+i,tanks[i].getFill());
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag,provider);
        burning=tag.getBoolean("burning");
        initialize(RocketParts.get(tag.getString("engine")),BlockPos.of(tag.getLong("offset")));
        solid = Math.clamp(tag.getInt("solid"),0,CAPACITY);
        for (int i=0;i<tanks.length;i++) tanks[i].setFill(Math.clamp(tag.getInt("tank"+i),0,CAPACITY));
    }
}


