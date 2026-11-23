package space.sableorbit;

import com.hbm.inventory.NTMItemHandler;
import com.hbm.inventory.fluid.FluidType;
import com.hbm.inventory.fluid.Fluids;
import com.hbm.inventory.fluid.tank.FluidTankNTM;
import com.hbm.items.machine.IItemFluidId;

final class AdjustableFuselageTank extends FluidTankNTM {
    private boolean filtered;
    AdjustableFuselageTank(int capacity) { super(Fluids.NONE, capacity); }
    boolean filtered() { return filtered; }

    void restore(FluidType type, boolean filtered) {
        this.filtered = filtered && type != null && type != Fluids.NONE;
        setTankTypeByIdentifier(type);
    }

    void updateIdentifier(NTMItemHandler inventory, int slot) {
        var stack = inventory.getStackInSlot(slot);
        if (!(stack.getItem() instanceof IItemFluidId identifier) || !inventory.getStackInSlot(slot + 1).isEmpty()) return;
        var type = identifier.getType(null, 0, 0, 0, stack);
        // Inserting an identifier for liquid already held must pin that liquid as well.
        if (!filtered && type != Fluids.NONE && type == getDeclaredFluid()) {
            inventory.setStackInSlot(slot + 1, stack);
            inventory.setStackInSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
            filtered = true;
        } else if (setType(inventory, slot, slot + 1)) filtered = getDeclaredFluid() != Fluids.NONE;
    }

    @Override public boolean accepts(FluidType type) {
        return getDeclaredFluid() == Fluids.NONE ? type != null && type != Fluids.NONE : super.accepts(type);
    }

    @Override public boolean joins(FluidType type) { return accepts(type); }

    @Override public int receive(FluidType type, int amount) {
        if (amount > 0 && getDeclaredFluid() == Fluids.NONE && accepts(type)) setTankTypeByIdentifier(type);
        return super.receive(type, amount);
    }

    @Override public void setFill(int amount) {
        super.setFill(amount);
        if (getFill() == 0 && !filtered && getDeclaredFluid() != Fluids.NONE) setTankTypeByIdentifier(Fluids.NONE);
    }
}
