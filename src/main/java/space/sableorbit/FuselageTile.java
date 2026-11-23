package space.sableorbit;

import com.hbm.api.energymk2.IEnergyHandlerMK2.ConnectionPriority;
import com.hbm.api.fluidmk2.IFluidMultiUserMK2;
import com.hbm.api.fluidmk2.IFluidPipeMK2;
import com.hbm.api.fluidmk2.IFluidReceiverMK2;
import com.hbm.api.fluidmk2.IFluidProviderMK2;
import com.hbm.api.fluidmk2.IFluidStandardTransceiverMK2;
import com.hbm.api.fluidmk2.IFluidUserMK2;
import com.hbm.blocks.network.BlockFluidDuct;
import com.hbm.capability.CapRole;
import com.hbm.capability.NativeContacts;
import com.hbm.inventory.fluid.FluidType;
import com.hbm.inventory.fluid.Fluids;
import com.hbm.inventory.fluid.tank.FluidTankNTM;
import com.hbm.lib.ForgeDirection;
import com.hbm.tileentity.TileEntityInventoryBase;
import com.hbm.tileentity.machine.MachineFluidContainerIO;
import com.hbm.tileentity.machine.TileEntityMachineFluidTank;
import com.ntmspace.rocket.RocketPart;
import com.ntmspace.rocket.RocketParts;
import dev.golem.core.data.DataIn;
import dev.golem.core.data.DataOut;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

class FuselageTile extends TileEntityInventoryBase
        implements MenuProvider, IFluidStandardTransceiverMK2, IFluidMultiUserMK2, IFluidHandler {
    static final int SLOTS_PER_TANK = 4;
    private static final FluidTankNTM[] NO_TANKS = {};

    enum Mode {
        INPUT("input"), INPUT_EXPORT("input_export"), EXPORT("export");

        private static final Mode[] VALUES = values();
        final String translationKey;

        Mode(String key) { translationKey = "gui.sableorbits.fuselage." + key; }

        static Mode byId(int id) { return id >= 0 && id < VALUES.length ? VALUES[id] : INPUT; }
    }

    record Port(BlockPos cell, Direction face) {}

    private RocketPart part;
    private BlockPos offset = BlockPos.ZERO;
    private FluidTankNTM[] tanks = NO_TANKS;
    private List<BlockPos> cells = List.of();
    private List<Port> ports = List.of();
    private Mode mode = Mode.INPUT;
    private boolean refreshOnTick;

    FuselageInventoryPort inventoryPort() {
        Direction front = getBlockState().getValue(FuselageBlock.FACING);
        if (!isEnd(front) && !isEnd(front.getOpposite())) return null;
        var root = master();
        return root == null ? null : new FuselageInventoryPort(this, root);
    }

    net.neoforged.neoforge.items.IItemHandler itemHandler(Direction face) {
        if (face != null && !isEnd(face)) return null;
        var port = inventoryPort();
        if (port == null) return null;
        Direction front = getBlockState().getValue(FuselageBlock.FACING);
        Direction end = face != null ? face : isEnd(front) ? front : front.getOpposite();
        return new net.neoforged.neoforge.items.wrapper.SidedInvWrapper(port, end);
    }

    FuselageTile(BlockPos pos, BlockState state) {
        this(Fuselages.TILE.get(), pos, state, 12);
    }

    FuselageTile(BlockEntityType<?> type, BlockPos pos, BlockState state, int slots) {
        super(type, pos, state, slots);
    }

    static FluidTankNTM[] createTanks(RocketPart part) {
        if (part == null) return NO_TANKS;
        if ("ANY".equals(part.fuel())) return new FluidTankNTM[]{new AdjustableFuselageTank(Fuselages.capacity(part)), new AdjustableFuselageTank(Fuselages.capacity(part))};
        return Stream.of(part.getFuel(), part.getOxidizer())
            .filter(fluid -> fluid != null && fluid != Fluids.NONE)
            .distinct()
            .map(fluid -> {
                var tank = new FluidTankNTM(fluid, Fuselages.capacity(part));
                tank.setTankTypeByIdentifier(fluid);
                return tank;
            }).toArray(FluidTankNTM[]::new);
    }

    void initialize(RocketPart part, BlockPos toCore) {
        setPart(part, toCore);
        setChanged();
    }

    private void setPart(RocketPart part, BlockPos toCore) {
        this.part = part;
        offset = toCore;
        tanks = isMaster() ? createTanks(part) : NO_TANKS;
        cells = List.of();
        ports = List.of();
        if (part == null || !isMaster()) return;
        for (FluidTankNTM tank : tanks) tank.bindOwner(this);
        Direction front = getBlockState().getValue(FuselageBlock.FACING);
        Direction back = front.getOpposite();
        int last = Math.max(1, (int) Math.ceil(part.height())) - 1;
        cells = List.copyOf(Engines.cells(part, worldPosition, front));
        var faces = new ArrayList<Port>();
        for (BlockPos cell : cells) {
            int depth = axialDistance(cell.subtract(worldPosition), front);
            if (depth == 0) faces.add(new Port(cell, back));
            if (depth == last) faces.add(new Port(cell, front));
        }
        ports = List.copyOf(faces);
    }

    RocketPart part() { return part; }
    boolean isMaster() { return offset.equals(BlockPos.ZERO); }
    BlockPos masterPos() { return worldPosition.offset(offset); }
    Mode mode() { return mode; }
    List<BlockPos> cells() { return cells; }
    List<Port> endPorts() { return ports; }

    FuselageTile master() {
        if (part == null || isRemoved()) return null;
        if (isMaster()) return this;
        BlockPos root = masterPos();
        return level != null && level.hasChunkAt(root) && level.getBlockEntity(root) instanceof FuselageTile tile
                && tile.isMaster() && !tile.isRemoved() && tile.part == part
                && tile.getBlockState().getValue(FuselageBlock.FACING) == getBlockState().getValue(FuselageBlock.FACING)
            ? tile : null;
    }

    private static int axialDistance(BlockPos offset, Direction facing) {
        return offset.getX() * facing.getStepX() + offset.getY() * facing.getStepY() + offset.getZ() * facing.getStepZ();
    }

    boolean isEnd(Direction side) {
        if (side == null || part == null) return false;
        Direction front = getBlockState().getValue(FuselageBlock.FACING);
        int depth = -axialDistance(offset, front);
        return side == front.getOpposite() && depth == 0
            || side == front && depth == Math.max(1, (int) Math.ceil(part.height())) - 1;
    }

    private FuselageTile endTank(Direction side) { return isEnd(side) ? master() : null; }

    @Override public boolean canConnect(FluidType fluid, ForgeDirection side) {
        var root = side == null ? null : endTank(side.toDirection());
        if (root == null) return false;
        if (fluid == null || fluid == Fluids.NONE) return root.tanks.length != 0;
        return root.tankFor(fluid) != null;
    }

    @Override public boolean exposesFluidContact(FluidType fluid, ForgeDirection side, boolean receiving) {
        Direction face = side == null ? null : side.toDirection();
        if (face == null || part == null) return false;
        // HBM also queries the resolved tank after checking the actual section's port.
        var root = isMaster() && face.getAxis() == getBlockState().getValue(FuselageBlock.FACING).getAxis()
            ? master() : endTank(face);
        if (root == null || !(receiving ? root.canReceive() : root.canExport())) return false;
        return fluid == null || fluid == Fluids.NONE ? root.tanks.length != 0 : root.tankFor(fluid) != null;
    }

    @Override public IFluidUserMK2 fluidUserAt(BlockPos pos, Direction side) {
        return worldPosition.equals(pos) ? endTank(side) : null;
    }

    @Override public boolean isLoaded() { return level != null && !isRemoved(); }

    @Override public FluidTankNTM[] getAllTanks() { return tanks; }
    @Override public FluidTankNTM[] getReceivingTanks() { return canReceive() ? tanks : NO_TANKS; }
    @Override public FluidTankNTM[] getSendingTanks() { return canExport() ? tanks : NO_TANKS; }
    boolean canReceive() { return mode != Mode.EXPORT; }
    boolean canExport() { return mode != Mode.INPUT; }

    private FluidTankNTM tankFor(FluidType fluid) {
        FluidTankNTM candidate = null;
        for (FluidTankNTM tank : tanks) if (tank.joins(fluid)) {
            if (tank.getDeclaredFluid() != Fluids.NONE && tank.getFill() < tank.getMaxFill()) return tank;
            if (candidate == null || candidate.getFill() == candidate.getMaxFill()) candidate = tank;
        }
        return candidate;
    }

    @Override public long getReceiverSpeed(FluidType fluid, int pressure) {
        long space = getDemand(fluid, pressure);
        return space > 0 ? Math.max(500, space / 100) : 0;
    }

    @Override public long getProviderSpeed(FluidType fluid, int pressure) {
        long amount = getFluidAvailable(fluid, pressure);
        return amount > 0 ? Math.max(500, amount / 100) : 0;
    }

    @Override public ConnectionPriority getFluidPriority() {
        return mode == Mode.INPUT_EXPORT ? ConnectionPriority.LOW : ConnectionPriority.NORMAL;
    }

    @Override public long transferFluid(FluidType fluid, int pressure, long amount) {
        if (amount <= 0) return amount;
        if (tanks.length == 2 && tanks[0] instanceof AdjustableFuselageTank) {
            long remaining = amount;
            while (canReceive() && remaining > 0) {
                var tank = tankFor(fluid);
                if (tank == null || tank.getPressure() != pressure) break;
                int accepted = tank.receive(fluid, (int)Math.min(Integer.MAX_VALUE, remaining));
                if (accepted == 0) break;
                remaining -= accepted;
            }
            if (remaining < amount) setChanged();
            return remaining;
        }
        long remaining = IFluidStandardTransceiverMK2.super.transferFluid(fluid, pressure, amount);
        if (remaining < amount) setChanged();
        return remaining;
    }

    @Override public void useUpFluid(FluidType fluid, int pressure, long amount) {
        if (amount <= 0 || getFluidAvailable(fluid, pressure) <= 0) return;
        IFluidStandardTransceiverMK2.super.useUpFluid(fluid, pressure, amount);
        setChanged();
    }

    IFluidHandler fluidHandler(Direction side) {
        Direction front = getBlockState().getValue(FuselageBlock.FACING);
        var root = side == null ? endTank(isEnd(front) ? front : front.getOpposite()) : endTank(side);
        return root != null && root.tanks.length > 0 ? this : null;
    }

    @Override public int getTanks() {
        var root = master();
        return root == null ? 0 : root.tanks.length;
    }

    private FluidTankNTM tankAt(int index) {
        var root = master();
        return root != null && index >= 0 && index < root.tanks.length ? root.tanks[index] : null;
    }

    @Override public FluidStack getFluidInTank(int index) {
        var tank = tankAt(index);
        return tank == null || tank.getFill() == 0 || tank.getFluid().getFluid() == null
            ? FluidStack.EMPTY : new FluidStack(tank.getFluid().getFluid(), tank.getFill());
    }

    @Override public int getTankCapacity(int index) {
        var tank = tankAt(index);
        return tank == null ? 0 : tank.getMaxFill();
    }

    @Override public boolean isFluidValid(int index, FluidStack stack) {
        var tank = tankAt(index);
        return tank != null && !stack.isEmpty() && tank.accepts(stack.getFluid());
    }

    @Override public int fill(FluidStack stack, FluidAction action) {
        var root = master();
        if (root == null || !root.canReceive() || stack.isEmpty()) return 0;
        var fluid = Fluids.fromVanillaExact(stack.getFluid());
        int amount = stack.getAmount();
        return action.execute() ? amount - (int)root.transferFluid(fluid, 0, amount)
            : (int)Math.min(amount, root.getDemand(fluid, 0));
    }

    @Override public FluidStack drain(FluidStack stack, FluidAction action) {
        if (stack.isEmpty()) return FluidStack.EMPTY;
        var root = master();
        if (root == null || !root.canExport()) return FluidStack.EMPTY;
        for (FluidTankNTM tank : root.tanks) {
            if (tank.getFill() > 0 && tank.provides(stack.getFluid())) {
                return root.drainTank(tank, stack.getAmount(), action);
            }
        }
        return FluidStack.EMPTY;
    }

    @Override public FluidStack drain(int amount, FluidAction action) {
        var root = master();
        if (root == null || !root.canExport()) return FluidStack.EMPTY;
        for (FluidTankNTM tank : root.tanks) {
            if (tank.getFill() > 0 && tank.getFluid().getFluid() != null) return root.drainTank(tank, amount, action);
        }
        return FluidStack.EMPTY;
    }

    private FluidStack drainTank(FluidTankNTM tank, int amount, FluidAction action) {
        int extracted = Math.min(amount, tank.getFill());
        if (extracted <= 0 || tank.getFluid().getFluid() == null) return FluidStack.EMPTY;
        var result = new FluidStack(tank.getFluid().getFluid(), extracted);
        if (result.isEmpty()) return result;
        if (action.execute()) {
            tank.setFill(tank.getFill() - extracted);
            setChanged();
        }
        return result;
    }

    @Override public void onLoad() {
        super.onLoad();
        refreshOnTick = true;
    }

    @Override public void refreshFluidPorts() {
        refreshOnTick = false;
        if (!(level instanceof ServerLevel server) || !Fuselages.isLiquidFuselage(part)) return;
        if (isMaster()) {
            for (Port port : ports) refreshPort(server, port.cell(), port.face());
        } else {
            Direction front = getBlockState().getValue(FuselageBlock.FACING);
            if (isEnd(front)) refreshPort(server, worldPosition, front);
            if (isEnd(front.getOpposite())) refreshPort(server, worldPosition, front.getOpposite());
        }
    }

    private static void refreshPort(ServerLevel server, BlockPos cell, Direction face) {
        if (!server.hasChunkAt(cell)) return;
        server.invalidateCapabilities(cell);
        BlockPos adjacent = cell.relative(face);
        if (server.hasChunkAt(adjacent)) BlockFluidDuct.portChanged(server, adjacent);
    }

    void tick() {
        if (!(level instanceof ServerLevel server)) return;
        if (refreshOnTick) refreshFluidPorts();
        if (tanks.length == 0) return;
        for (int index = 0; index < tanks.length; index++) {
            FluidTankNTM tank = tanks[index];
            int slot = index * SLOTS_PER_TANK;
            int previous = tank.getFill();
            if (tank instanceof AdjustableFuselageTank adjustable) {
                adjustable.updateIdentifier(inventory, 8 + 2 * index);
                MachineFluidContainerIO.identify(tank, inventory, slot);
            }
            MachineFluidContainerIO.load(tank, inventory, slot, slot + 1);
            MachineFluidContainerIO.unload(tank, inventory, slot + 2, slot + 3);
            if (tank.getFill() != previous) setChanged();
            if (tank instanceof AdjustableFuselageTank adjustable && !adjustable.filtered() && tank.getFill() == 0) tank.setFill(0);
            if (canReceive() && tank.getFill() < tank.getMaxFill()) pullFluid(server, tank);
            if (canExport() && tank.getFill() > 0) pushFluid(server, tank);
        }
    }

    private void pullFluid(ServerLevel server, FluidTankNTM tank) {
        for (Port port : ports) {
            var target = port.cell().relative(port.face());
            if (!server.hasChunkAt(target)) continue;
            var neighbor = server.getBlockEntity(target);
            // Ducts schedule their own transfers. Adjacent fuselages share through pushFluid.
            if (neighbor instanceof FuselageTile || neighbor instanceof IFluidPipeMK2) continue;
            var side = port.face().getOpposite();
            if (neighbor == null && server.isEmptyBlock(target)) continue;
            FluidType[] types = tank.connectionTypes();
            if (tank instanceof AdjustableFuselageTank && tank.getDeclaredFluid() == Fluids.NONE) {
                var source = neighbor instanceof IFluidUserMK2 nativeTile ? nativeTile : NativeContacts.fluidKindAt(server, target, CapRole.FLUID_OUT);
                if (source != null) types = java.util.Arrays.stream(source.getAllTanks()).filter(t -> t.getFill() > 0)
                    .map(FluidTankNTM::getFluid).toArray(FluidType[]::new);
            }
            for (FluidType fluid : types) {
                if (tankFor(fluid) != tank) continue;
                var user = NativeContacts.fluidAt(server, target, side, fluid, CapRole.FLUID_OUT);
                if (!(user instanceof IFluidProviderMK2 provider)) continue;
                int pressure = tank.getPressure();
                long offer = Math.min(provider.getFluidAvailable(fluid, pressure), provider.getProviderSpeed(fluid, pressure));
                int amount = (int)Math.min(offer, Math.min(tank.getMaxFill() - tank.getFill(), getReceiverSpeed(fluid, pressure)));
                if (canExport() && user instanceof IFluidReceiverMK2 receiver && receiver.getDemand(fluid, pressure) > 0) {
                    for (var source : provider.getAllTanks()) if (source.provides(fluid)) {
                        amount = Math.min(amount, balanceAmount(source, tank));
                        break;
                    }
                }
                if (amount <= 0) continue;
                int accepted = tank.receive(fluid, amount);
                provider.useUpFluid(fluid, pressure, accepted);
                setChanged();
                return;
            }
            if (!(neighbor instanceof IFluidUserMK2)) {
                var handler = server.getCapability(Capabilities.FluidHandler.BLOCK, target, side);
                if (handler != null && pullFromHandler(tank, handler)) return;
            }
        }
    }

    private boolean pullFromHandler(FluidTankNTM tank, IFluidHandler handler) {
        for (int i = 0; i < handler.getTanks(); i++) {
            var stored = handler.getFluidInTank(i);
            if (stored.isEmpty() || !tank.accepts(stored.getFluid())) continue;
            var type = Fluids.fromVanillaExact(stored.getFluid());
            if (tankFor(type) != tank) continue;
            int amount = (int)Math.min(tank.getMaxFill() - tank.getFill(), getReceiverSpeed(type, tank.getPressure()));
            if (canExport() && handler.fill(stored.copyWithAmount(1), FluidAction.SIMULATE) > 0)
                amount = Math.min(amount, balanceAmount(stored.getAmount(), handler.getTankCapacity(i), tank.getFill(), tank.getMaxFill()));
            if (amount <= 0) continue;
            var extracted = handler.drain(stored.copyWithAmount(amount), FluidAction.EXECUTE);
            if (extracted.isEmpty()) continue;
            tank.receive(type, extracted.getAmount());
            setChanged();
            return true;
        }
        return false;
    }

    private void pushFluid(ServerLevel server, FluidTankNTM tank) {
        int budget = (int) Math.min(tank.getFill(), getProviderSpeed(tank.getFluid(), tank.getPressure()));
        // Serve machines first so adjacent two-way tanks cannot take fuel away from a hungry engine.
        for (Port port : ports) {
            if (budget == 0) break;
            BlockPos target = port.cell().relative(port.face());
            if (!server.hasChunkAt(target)) continue;
            var neighbor = server.getBlockEntity(target);
            if (neighbor instanceof FuselageTile || neighbor instanceof IFluidPipeMK2) continue;
            if (neighbor == null && server.isEmptyBlock(target)) continue;
            int accepted = pushToMachine(server, tank, target, port.face().getOpposite(), budget);
            tank.setFill(tank.getFill() - accepted);
            budget -= accepted;
            if (accepted != 0) setChanged();
        }
        for (Port port : ports) {
            if (budget == 0) break;
            BlockPos target = port.cell().relative(port.face());
            if (!server.hasChunkAt(target) || !(server.getBlockEntity(target) instanceof FuselageTile piece)) continue;
            var other = piece.endTank(port.face().getOpposite());
            if (other == null || other == this || !other.canReceive()) continue;
            var receiving = other.tankFor(tank.getFluid());
            if (receiving == null) continue;
            int amount = Math.min(budget, receiving.getMaxFill() - receiving.getFill());
            if (canReceive() && other.canExport()) {
                long difference = (long) tank.getFill() * receiving.getMaxFill() - (long) receiving.getFill() * tank.getMaxFill();
                if (difference <= 0) continue;
                long balance = difference / ((long) tank.getMaxFill() + receiving.getMaxFill());
                amount = (int) Math.min(amount, Math.max(1, balance));
            }
            if (amount <= 0) continue;
            int accepted = amount - (int) other.transferFluid(tank.getFluid(), tank.getPressure(), amount);
            tank.setFill(tank.getFill() - accepted);
            budget -= accepted;
            if (accepted != 0) setChanged();
        }
    }

    private static int balanceAmount(FluidTankNTM source, FluidTankNTM destination) {
        return balanceAmount(source.getFill(), source.getMaxFill(), destination.getFill(), destination.getMaxFill());
    }

    private static int balanceAmount(int source, int sourceCapacity, int destination, int destinationCapacity) {
        long difference = (long)source * destinationCapacity - (long)destination * sourceCapacity;
        return (int)Math.max(0L, difference / ((long)sourceCapacity + destinationCapacity));
    }

    private int pushToMachine(ServerLevel server, FluidTankNTM tank, BlockPos pos, Direction side, int amount) {
        FluidType fluid = tank.getFluid();
        int pressure = tank.getPressure();
        var nativeUser = NativeContacts.fluidAt(server, pos, side, fluid, CapRole.FLUID_IN);
        if (nativeUser instanceof IFluidReceiverMK2 receiver) {
            long offered = Math.min(amount, Math.min(receiver.getDemand(fluid, pressure), receiver.getReceiverSpeed(fluid, pressure)));
            if (canReceive() && receiver instanceof IFluidProviderMK2 provider
                    && provider.getFluidAvailable(fluid, pressure) > 0) {
                for (var target : receiver.getAllTanks()) if (target.accepts(fluid)) {
                    offered = Math.min(offered, balanceAmount(tank, target));
                    break;
                }
            }
            return offered > 0 ? (int) (offered - receiver.transferFluid(fluid, pressure, offered)) : 0;
        }
        if (fluid.getFluid() == null) return 0;
        var handler = server.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        if (handler == null) return 0;
        if (canReceive()) {
            for (int i = 0; i < handler.getTanks(); i++) {
                var stored = handler.getFluidInTank(i);
                if (!stored.isEmpty() && stored.getFluid() == fluid.getFluid()
                        && !handler.drain(stored.copyWithAmount(1), FluidAction.SIMULATE).isEmpty()) {
                    amount = Math.min(amount, balanceAmount(tank.getFill(), tank.getMaxFill(), stored.getAmount(), handler.getTankCapacity(i)));
                    break;
                }
            }
        }
        return amount > 0 ? handler.fill(new FluidStack(fluid.getFluid(), amount), FluidAction.EXECUTE) : 0;
    }

    void cycleMode() {
        mode = Mode.VALUES[(mode.ordinal() + 1) % Mode.VALUES.length];
        refreshFluidPorts();
        setChanged();
    }

    static boolean validContainer(FluidTankNTM[] tanks, int slot, ItemStack stack) {
        if (slot >= 8 && slot < 12 && tanks.length == 2 && tanks[0] instanceof AdjustableFuselageTank)
            return slot % 2 == 0 && stack.getItem() instanceof com.hbm.items.machine.IItemFluidId;
        if (slot < 0 || slot >= tanks.length * SLOTS_PER_TANK) return false;
        var tank = tanks[slot / SLOTS_PER_TANK];
        if (tank instanceof AdjustableFuselageTank && tank.getDeclaredFluid() == Fluids.NONE)
            return slot % SLOTS_PER_TANK == 0 && tank.accepts(MachineFluidContainerIO.heldFluid(stack));
        return switch (slot % SLOTS_PER_TANK) {
            case 0 -> TileEntityMachineFluidTank.isDrainable(tank, stack);
            case 2 -> TileEntityMachineFluidTank.isFillable(tank, stack);
            default -> false;
        };
    }

    @Override public boolean isItemValidForSlot(int slot, ItemStack stack) { return validContainer(tanks, slot, stack); }

    boolean canUseMenu(Player player, BlockPos anchor) {
        if (level != player.level() || isRemoved() || part == null || !level.hasChunkAt(anchor)
                || !(level.getBlockEntity(anchor) instanceof FuselageTile tile) || tile.master() != this) return false;
        var worldAnchor = Sable.HELPER.projectOutOfSubLevel(level, anchor.getCenter());
        var worldPlayer = Sable.HELPER.projectOutOfSubLevel(level, player.position());
        return worldPlayer.distanceToSqr(worldAnchor) <= 64.0;
    }

    @Override public Component getDisplayName() {
        return part == null ? Component.translatable("block.sableorbits.rocket_fuselage") : part.item().getDescription();
    }

    void openMenu(ServerPlayer player, BlockPos anchor) {
        if (!canUseMenu(player, anchor)) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, user) -> new FuselageMenu(id, inventory, this, anchor), getDisplayName()),
            buffer -> buffer.writeUtf(part.id()));
    }

    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new FuselageMenu(id, inventory, this, worldPosition);
    }

    CompoundTag saveTankContents() {
        var tag = new CompoundTag();
        for (int i = 0; i < tanks.length; i++) if (tanks[i] instanceof AdjustableFuselageTank tank) {
            Fluids.write(tag, "type" + i, tank.getDeclaredFluid());
            tag.putBoolean("filtered" + i, tank.filtered());
        }
        if (mode != Mode.INPUT) tag.putInt("mode", mode.ordinal());
        for (int i = 0; i < tanks.length; i++) {
            int fill = tanks[i].getFill();
            if (fill > 0) tag.putInt("tank" + i, fill);
        }
        return tag;
    }

    void loadTankContents(CompoundTag tag) {
        for (int i = 0; i < tanks.length; i++) if (tanks[i] instanceof AdjustableFuselageTank tank)
            tank.restore(Fluids.readExact(tag, "type" + i), tag.getBoolean("filtered" + i));
        mode = Mode.byId(tag.getInt("mode"));
        for (int i = 0; i < tanks.length; i++) tanks[i].setFill(Math.clamp(tag.getInt("tank" + i), 0, tanks[i].getMaxFill()));
        setChanged();
    }

    @Override protected void saveData(DataOut data) {
        super.saveData(data);
        if (part != null) data.putString("part", part.id());
        data.putLong("offset", offset.asLong());
        if (!isMaster() || part == null) return;
        for (int i = 0; i < tanks.length; i++) if (tanks[i] instanceof AdjustableFuselageTank tank) {
            Fluids.write(data, "type" + i, tank.getDeclaredFluid());
            data.putBoolean("filtered" + i, tank.filtered());
        }
        data.putInt("mode", mode.ordinal());
        for (int i = 0; i < tanks.length; i++) data.putInt("tank" + i, tanks[i].getFill());
    }

    @Override protected void loadData(DataIn data) {
        super.loadData(data);
        setPart(RocketParts.get(data.getStringOr("part", "")), BlockPos.of(data.getLongOr("offset", 0)));
        for (int i = 0; i < tanks.length; i++) if (tanks[i] instanceof AdjustableFuselageTank tank)
            tank.restore(Fluids.readExact(data, "type" + i), data.getBooleanOr("filtered" + i, false));
        mode = Mode.byId(data.getIntOr("mode", 0));
        for (int i = 0; i < tanks.length; i++) tanks[i].setFill(Math.clamp(data.getIntOr("tank" + i, 0), 0, tanks[i].getMaxFill()));
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        if (part != null) tag.putString("part", part.id());
        tag.putLong("offset", offset.asLong());
        return tag;
    }

    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
