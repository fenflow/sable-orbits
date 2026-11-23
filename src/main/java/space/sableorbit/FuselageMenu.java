package space.sableorbit;

import com.hbm.inventory.fluid.tank.FluidTankNTM;
import com.ntmspace.rocket.RocketParts;
import dev.golem.core.world.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class FuselageMenu extends AbstractContainerMenu {
    static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "sableorbits");
    static final Supplier<MenuType<FuselageMenu>> TYPE = MENUS.register("rocket_fuselage",
        () -> IMenuTypeExtension.create(FuselageMenu::clientMenu));
    static final int TANK_WIDTH = 113;

    private final FuselageTile tile;
    private final BlockPos anchor;
    private final FluidTankNTM[] tanks;
    private final DataSlot mode;
    private final DataSlot[] typeLow, typeHigh;
    private final DataSlot[] amountsLow;
    private final DataSlot[] amountsHigh;

    private static FuselageMenu clientMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        var part = data == null || !data.isReadable() ? null : RocketParts.get(data.readUtf());
        return new FuselageMenu(id, inventory, null, BlockPos.ZERO, FuselageTile.createTanks(part));
    }

    FuselageMenu(int id, Inventory inventory, FuselageTile tile, BlockPos anchor) {
        this(id, inventory, tile, anchor, tile.getAllTanks());
    }

    private FuselageMenu(int id, Inventory inventory, FuselageTile tile, BlockPos anchor, FluidTankNTM[] tanks) {
        super(TYPE.get(), id);
        this.tile = tile;
        this.anchor = anchor;
        this.tanks = tanks;
        int machineSlots = machineSlots();
        Container machine = tile == null ? new SimpleContainer(machineSlots) : tile;
        mode = watched(() -> tile.mode().ordinal());
        typeLow = new DataSlot[tanks.length];
        typeHigh = new DataSlot[tanks.length];
        amountsLow = new DataSlot[tanks.length];
        amountsHigh = new DataSlot[tanks.length];
        for (int i = 0; i < tanks.length; i++) {
            FluidTankNTM tank = tanks[i];
            if (adjustable()) {
                typeLow[i] = watched(() -> (short)com.hbm.inventory.fluid.Fluids.networkId(tank.getDeclaredFluid()));
                typeHigh[i] = watched(() -> com.hbm.inventory.fluid.Fluids.networkId(tank.getDeclaredFluid()) >>> 16);
            }
            // Vanilla writes menu data as signed shorts. Keep both halves of large tank levels.
            amountsLow[i] = watched(() -> (short) tank.getFill());
            amountsHigh[i] = watched(() -> tank.getFill() >>> 16);
            int slot = i * FuselageTile.SLOTS_PER_TANK;
            int x = tankLeft(i);
            addTankSlot(machine, slot, x + 5, tankTop() + 3);
            addTankSlot(machine, slot + 1, x + 5, tankTop() + 39);
            addTankSlot(machine, slot + 2, x + 95, tankTop() + 3);
            addTankSlot(machine, slot + 3, x + 95, tankTop() + 39);
        }
        if (adjustable()) for (int i = 0; i < tanks.length; i++) {
            addTankSlot(machine, 8 + i * 2, tankLeft(i) - 22, tankTop() + 3);
            addTankSlot(machine, 9 + i * 2, tankLeft(i) - 22, tankTop() + 39);
        }
        Compat.addPlayerInventory(this::addSlot, inventory, (width() - 160) / 2, inventoryY());
    }

    private DataSlot watched(IntSupplier supplier) {
        if (tile == null) return addDataSlot(DataSlot.standalone());
        return addDataSlot(new DataSlot() {
            @Override public int get() { return supplier.getAsInt(); }
            @Override public void set(int value) {}
        });
    }

    private void addTankSlot(Container machine, int slot, int x, int y) {
        addSlot(new Slot(machine, slot, x, y) {
            @Override public boolean mayPlace(ItemStack stack) { return FuselageTile.validContainer(tanks, slot, stack); }
        });
    }

    boolean adjustable() { return tanks.length == 2 && tanks[0] instanceof AdjustableFuselageTank; }
    private int machineSlots() { return adjustable() ? 12 : tanks.length * FuselageTile.SLOTS_PER_TANK; }
    int width() { return tanks.length == 2 ? 176 + TANK_WIDTH + (adjustable() ? 27 : 0) : 176; }
    int height() { return tankTop() + 152; }
    int tankCount() { return tanks.length; }
    int tankLeft(int index) { return 30 + index * (TANK_WIDTH + (adjustable() ? 27 : 0)); }
    int tankTop() { return tanks.length == 2 ? 26 : 14; }
    int inventoryY() { return tankTop() + 70; }
    FuselageTile.Mode mode() { return FuselageTile.Mode.byId(mode.get()); }

    FluidTankNTM tank(int index) {
        FluidTankNTM tank = tanks[index];
        if (tile == null) {
            if (tank instanceof AdjustableFuselageTank adjustable) {
                int type = (typeHigh[index].get() << 16) | (typeLow[index].get() & 0xffff);
                adjustable.restore(com.hbm.inventory.fluid.Fluids.byNetworkIdExact(type), true);
            }
            int amount = amountsHigh[index].get() << 16 | amountsLow[index].get() & 0xffff;
            tank.setFill(Math.clamp(amount, 0, tank.getMaxFill()));
        }
        return tank;
    }

    @Override public boolean stillValid(Player player) { return tile == null || tile.canUseMenu(player, anchor); }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (id != 0 || tile == null || !stillValid(player)) return false;
        tile.cycleMode();
        return true;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int machineSlots = machineSlots();
        boolean moved = index < machineSlots ? moveItemStackTo(stack, machineSlots, slots.size(), true)
            : moveItemStackTo(stack, 0, machineSlots, false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return original;
    }
}
