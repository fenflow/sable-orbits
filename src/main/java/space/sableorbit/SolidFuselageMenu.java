package space.sableorbit;

import dev.golem.core.world.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
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

final class SolidFuselageMenu extends AbstractContainerMenu {
    static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "sableorbits");
    static final Supplier<MenuType<SolidFuselageMenu>> TYPE = MENUS.register("solid_rocket_fuselage", () -> IMenuTypeExtension.create(
        (id, inventory, data) -> new SolidFuselageMenu(id, inventory, null, BlockPos.ZERO, data.readVarInt())));
    static final int WIDTH = 176;
    static final int HEIGHT = 174;
    static final int SLOT_X = 17;
    static final int SLOT_Y = 32;
    static final int INVENTORY_Y = 92;

    private final SolidFuselageTile tile;
    private final BlockPos anchor;
    private final int capacity;
    private final DataSlot mode;
    private final DataSlot chargeLow;
    private final DataSlot chargeHigh;

    SolidFuselageMenu(int id, Inventory inventory, SolidFuselageTile tile, BlockPos anchor) {
        this(id, inventory, tile, anchor, tile.capacity());
    }

    private SolidFuselageMenu(int id, Inventory inventory, SolidFuselageTile tile, BlockPos anchor, int capacity) {
        super(TYPE.get(), id);
        this.tile = tile;
        this.anchor = anchor;
        this.capacity = capacity;
        mode = watched(() -> tile.mode().ordinal());
        chargeLow = watched(() -> (short) tile.charge());
        chargeHigh = watched(() -> tile.charge() >>> 16);
        Container machine = tile == null ? new SimpleContainer(1) : tile;
        addSlot(new Slot(machine, 0, SLOT_X, SLOT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return SolidPropellants.units(stack) > 0; }
        });
        Compat.addPlayerInventory(this::addSlot, inventory, 8, INVENTORY_Y);
    }

    private DataSlot watched(IntSupplier get) {
        if (tile == null) return addDataSlot(DataSlot.standalone());
        return addDataSlot(new DataSlot() {
            @Override public int get() { return get.getAsInt(); }
            @Override public void set(int value) {}
        });
    }

    int capacity() { return capacity; }
    int charge() { return chargeHigh.get() << 16 | chargeLow.get() & 0xffff; }
    FuselageTile.Mode mode() { return FuselageTile.Mode.byId(mode.get()); }

    @Override public boolean stillValid(Player player) { return tile == null || tile.canUseMenu(player, anchor); }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (id != 0 || tile == null || !stillValid(player)) return false;
        tile.cycleMode();
        return true;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack = slot.getItem();
        var original = stack.copy();
        boolean moved = index == 0 ? moveItemStackTo(stack, 1, slots.size(), true) : moveItemStackTo(stack, 0, 1, false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        return original;
    }
}
