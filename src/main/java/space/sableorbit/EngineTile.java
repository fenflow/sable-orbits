package space.sableorbit;

import com.hbm.api.fluidmk2.IFluidMultiUserMK2;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.util.*;

final class EngineTile extends BlockEntity implements IFluidStandardReceiverMK2, IFluidMultiUserMK2, IFluidHandler {
    private static final double PHYSICAL_THRUST_SCALE = 0.01;
    private static final int CAPACITY = 16000;
    private static final int LIQUID_BURN_MULTIPLIER = 10;
    private static final int SOLID_BURN_PER_TICK = 10;
    private static final int[] SOLID_SLOTS = {0};
    private static final int[] NO_SLOTS = {};
    private RocketPart engine;
    private BlockPos offset = BlockPos.ZERO;
    private FluidTankNTM[] tanks = {};
    private int liquidBurnRate;
    private SolidInput solidInput;
    private int solid;
    private boolean burning;
    private boolean refreshPortsOnTick;
    private List<BlockPos> cells = List.of();
    EngineTile(BlockPos pos, BlockState state) { super(Engines.TILE.get(),pos,state); }
    void initialize(RocketPart part, BlockPos toCore) {
        engine = part;
        offset = toCore;
        if (part != null && offset.equals(BlockPos.ZERO)) {
            cells = Engines.cells(part,worldPosition,nozzle(), getBlockState().getValue(RocketPlacement.FOOTPRINT_ROTATION));
            tanks = java.util.stream.Stream.of(part.getFuel(),part.getOxidizer())
                .filter(f -> f != null && f != Fluids.NONE).map(f -> new FluidTankNTM(f,CAPACITY)).toArray(FluidTankNTM[]::new);
            for (var tank : tanks) tank.bindOwner(this);
            liquidBurnRate = LIQUID_BURN_MULTIPLIER * Math.max(1,
                (int)Math.ceil(part.getThrust() / (20.0 * 9.81 * Math.max(1, part.getISP()))));
        }
        if (part == null || !isMaster() || !"SOLID".equals(part.fuel())) solidInput = null;
        else if (solidInput == null) solidInput = new SolidInput();
        setChanged();
    }
    RocketPart engine() { return engine; }
    boolean isMaster() { return offset.equals(BlockPos.ZERO); }
    boolean isBurning() { return burning; }
    net.minecraft.world.phys.AABB bounds() {
        if (cells.isEmpty()) return new net.minecraft.world.phys.AABB(worldPosition);
        return new net.minecraft.world.phys.AABB(cells.getFirst()).minmax(new net.minecraft.world.phys.AABB(cells.getLast()));
    }
    Vec3 visualCenter() {
        var bounds = bounds();
        var facing = nozzle();
        double length = facing.getAxis().choose(bounds.getXsize(), bounds.getYsize(), bounds.getZsize());
        // The mesh's +Y end mounts against the back of the occupied volume.
        return bounds.getCenter().add(Vec3.atLowerCornerOf(facing.getNormal()).scale((engine.height() - length) * 0.5));
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider p) { var tag=saveWithoutMetadata(p);tag.putBoolean("burning",burning);return tag; }
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    Direction nozzle() { return getBlockState().getValue(EngineBlock.FACING); }
    org.joml.Quaternionf modelRotation() { return RocketPlacement.modelRotation(getBlockState(), nozzle().getOpposite()); }
    private BlockPos sectionOffset() { return offset.rotate(getBlockState().getValue(RocketPlacement.FOOTPRINT_ROTATION)); }
    private BlockPos masterPos() { return worldPosition.offset(sectionOffset()); }
    private boolean rearSection() { return sectionOffset().get(nozzle().getAxis()) == 0; }
    private boolean rearInlet(Direction side) { return side == nozzle().getOpposite() && rearSection(); }
    boolean inlet(Direction side) {
        if (side == null || engine == null) return false;
        return rearInlet(side) || (isMaster() && tanks.length > 1 && side.getAxis() != nozzle().getAxis());
    }
    private EngineTile master() {
        if (engine == null || isRemoved()) return null;
        if (isMaster()) return this;
        var masterPos = masterPos();
        return level != null && level.hasChunkAt(masterPos) && level.getBlockEntity(masterPos) instanceof EngineTile master
            && !master.isRemoved() && master.isMaster() && master.engine == engine && master.nozzle() == nozzle() ? master : null;
    }
    EngineTile fluidInlet(Direction side) {
        if (!inlet(side)) return null;
        var master = master();
        return master != null && master.tanks.length > 0 ? master : null;
    }
    EngineTile solidInlet(Direction side) {
        if (!rearInlet(side)) return null;
        var master = master();
        return master != null && master.solidInput != null ? master : null;
    }
    IItemHandler solidItemInlet(Direction side) {
        if (!(level instanceof ServerLevel)) return null;
        var master = solidInlet(side == null ? nozzle().getOpposite() : side);
        return master == null ? null : master.solidInput.handler;
    }
    WorldlyContainer solidContainer() {
        if (!(level instanceof ServerLevel)) return null;
        var master = solidInlet(nozzle().getOpposite());
        return master == null ? null : master.solidInput;
    }
    @Override public EngineTile fluidUserAt(BlockPos pos, Direction side) {
        return worldPosition.equals(pos) ? fluidInlet(side) : null;
    }
    @Override public void onLoad() {
        super.onLoad();
        // A rear section and its master can load in different chunks.
        refreshPortsOnTick = engine != null && rearSection();
        if (level instanceof ServerLevel server && !isMaster()) server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
    }

    void checkSection(ServerLevel level) {
        if (engine == null || isMaster()) return;
        var masterPos = masterPos();
        if (!level.hasChunkAt(masterPos)) {
            level.scheduleTick(worldPosition, getBlockState().getBlock(), 20);
        } else if (master() == null) {
            level.removeBlock(worldPosition, false);
        } else if (refreshPortsOnTick) {
            refreshPorts();
        }
    }

    void checkRemainingSections(ServerLevel level) {
        var block = getBlockState().getBlock();
        for (var cell : cells) {
            if (!cell.equals(worldPosition) && level.hasChunkAt(cell) && level.getBlockState(cell).is(block)) {
                level.scheduleTick(cell, block, 1);
            }
        }
    }
    void refreshPorts() {
        refreshPortsOnTick = false;
        var master = master();
        if (master == null) return;
        boolean liquid = master.tanks.length > 0;
        if (!isMaster()) {
            refreshOwnPorts(liquid);
            return;
        }
        var axis = nozzle().getAxis();
        for (var cell : cells) {
            if (cell.get(axis) == worldPosition.get(axis) && level.hasChunkAt(cell)
                    && level.getBlockEntity(cell) instanceof EngineTile piece && piece.master() == this) {
                piece.refreshOwnPorts(liquid);
            }
        }
    }
    private void refreshOwnPorts(boolean liquid) {
        refreshPortsOnTick = false;
        level.invalidateCapabilities(worldPosition);
        if (!liquid) return;
        for (Direction side : Direction.values()) {
            if (inlet(side)) com.hbm.blocks.network.BlockFluidDuct.portChanged(level, worldPosition.relative(side));
        }
    }
    @Override public boolean isLoaded() { return level != null && !isRemoved(); }
    @Override public boolean canConnect(FluidType fluid, ForgeDirection side) {
        var master = side == null ? null : fluidInlet(side.toDirection());
        if (master == null) return false;
        if (fluid == null) return true;
        for (var tank : master.tanks) if (tank.joins(fluid)) return true;
        return false;
    }
    @Override public boolean exposesFluidContact(FluidType fluid, ForgeDirection side, boolean receiving) {
        return receiving && canConnect(fluid,side);
    }
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
        if (isRemoved()) return 0;
        for (int i=0; i<tanks.length; i++) if (isFluidValid(i,stack)) {
            int accepted = Math.min(stack.getAmount(),CAPACITY-tanks[i].getFill());
            if (action.execute()) { tanks[i].setFill(tanks[i].getFill()+accepted); setChanged(); }
            return accepted;
        }
        return 0;
    }
    @Override public FluidStack drain(FluidStack f, FluidAction a) { return FluidStack.EMPTY; }
    @Override public FluidStack drain(int amount, FluidAction a) { return FluidStack.EMPTY; }
    private final class SolidInput extends SimpleContainer implements WorldlyContainer {
        private final IItemHandler handler = new InvWrapper(this) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        };
        SolidInput() { super(1); }
        private boolean active() { return solidInput == this && level instanceof ServerLevel && !isRemoved(); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot == 0 && active() && SolidPropellants.units(stack) > 0;
        }
        @Override public int[] getSlotsForFace(Direction side) {
            return rearInlet(side) && active() ? SOLID_SLOTS : NO_SLOTS;
        }
        @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
            return rearInlet(side) && canPlaceItem(slot, stack);
        }
        @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) { return false; }
        @Override public void setChanged() { EngineTile.this.setChanged(); }
    }
    int solidFuel() { return solid; }
    boolean hasSolidSpace() { return solid < CAPACITY; }
    int acceptSolid(int units) {
        if (!(level instanceof ServerLevel) || solidInput == null || isRemoved() || units <= 0) return 0;
        int accepted = Math.min(units, CAPACITY - solid);
        if (accepted == 0) return 0;
        solid += accepted;
        setChanged();
        return accepted;
    }
    boolean loadSolid(ItemStack stack) {
        int units = SolidPropellants.units(stack);
        return units > 0 && units <= CAPACITY - solid && acceptSolid(units) == units;
    }
    private void loadSolidInput() {
        if (solidInput == null) return;
        if (loadSolid(solidInput.getItem(0))) solidInput.removeItem(0, 1);
    }
    void dismantle(boolean drop) {
        if (level == null || engine == null) return;
        if (!isMaster()) {
            var root = master();
            if (root != null) root.dismantle(drop);
            return;
        }
        if (drop) {
            Block.popResource(level,worldPosition,new ItemStack(engine.item()));
            if (solidInput != null) {
                var input = solidInput.removeItemNoUpdate(0);
                Block.popResource(level, worldPosition, input);
            }
        }
        for (var cell : cells) if (level.getBlockEntity(cell) instanceof EngineTile piece
                && piece.masterPos().equals(worldPosition)) level.removeBlock(cell,false);
    }
    void tick() {
        if (!(level instanceof ServerLevel server) || !isMaster() || engine == null) return;
        if (refreshPortsOnTick) refreshPorts();
        loadSolidInput();
        boolean active = burn(server);
        if(burning!=active && !isRemoved()) {
            burning=active;
            level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
        }
    }
    private boolean burn(ServerLevel server) {
        if (OrbitSpaces.unoccupied(server)) return false;
        if (Math.floorMod(server.getGameTime() + worldPosition.hashCode(), 20) == 0) {
            for (var cell : cells) {
                if (server.hasChunkAt(cell) && !(server.getBlockEntity(cell) instanceof EngineTile section && section.master() == this)) {
                    dismantle(true);
                    return false;
                }
            }
        }
        if (tanks.length == 0) {
            if (!"SOLID".equals(engine.fuel()) || solid < SOLID_BURN_PER_TICK) return false;
        } else {
            for (var tank : tanks) if (tank.getFill() < liquidBurnRate) return false;
        }
        if (cells.stream().noneMatch(server::hasNeighborSignal)) return false;
        if (tanks.length == 0) solid -= SOLID_BURN_PER_TICK;
        else for (var tank : tanks) tank.setFill(tank.getFill() - liquidBurnRate);
        setChanged();
        var ship=Sable.HELPER.getContaining(level,worldPosition);
        double impulse = engine.getThrust() * PHYSICAL_THRUST_SCALE / 20.0;
        if (ship instanceof ServerSubLevel sub) {
            var handle = RigidBodyHandle.of(sub);
            if (handle != null && handle.isValid()) handle.applyImpulseAtPoint(bounds().getCenter(),
                Vec3.atLowerCornerOf(nozzle().getOpposite().getNormal()).scale(impulse));
        }
        ExhaustBlast.apply(server, this, ship, impulse);
        return true;
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag,provider);
        if (engine != null) tag.putString("engine",engine.id());
        tag.putLong("offset",offset.asLong());
        tag.putInt("solid",solid);
        if (solidInput != null && !solidInput.getItem(0).isEmpty()) {
            tag.put("solidInput", solidInput.getItem(0).save(provider));
        }
        for (int i=0;i<tanks.length;i++) tag.putInt("tank"+i,tanks[i].getFill());
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag,provider);
        burning=tag.getBoolean("burning");
        initialize(RocketParts.get(tag.getString("engine")),BlockPos.of(tag.getLong("offset")));
        solid = Math.clamp(tag.getInt("solid"),0,CAPACITY);
        if (solidInput != null) {
            var input = ItemStack.parseOptional(provider, tag.getCompound("solidInput"));
            solidInput.setItem(0, SolidPropellants.units(input) > 0 ? input : ItemStack.EMPTY);
        }
        for (int i=0;i<tanks.length;i++) tanks[i].setFill(Math.clamp(tag.getInt("tank"+i),0,CAPACITY));
    }
}


