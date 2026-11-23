package space.sableorbit;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

record FuselageInventoryPort(FuselageTile section, FuselageTile tank) implements WorldlyContainer {
    private static final int[] NONE = {}, SOLID = {0}, SINGLE = {0, 1, 2, 3}, DUAL = {0, 1, 2, 3, 4, 5, 6, 7}, ADJUSTABLE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private boolean connected(Direction face) { return section.isEnd(face) && section.master() == tank; }

    @Override public int[] getSlotsForFace(Direction face) {
        if (!connected(face)) return NONE;
        if (tank instanceof SolidFuselageTile) return SOLID;
        if ("ANY".equals(tank.part().fuel())) return ADJUSTABLE;
        return tank.getAllTanks().length == 2 ? DUAL : SINGLE;
    }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face) {
        return connected(face) && canPlaceItem(slot, stack);
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        return tank.canReceive() && tank.isItemValidForSlot(slot, stack);
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face) {
        return connected(face) && !(tank instanceof SolidFuselageTile) && slot % 2 == 1;
    }
    @Override public int getContainerSize() { return tank.getContainerSize(); }
    @Override public boolean isEmpty() { return tank.isEmpty(); }
    @Override public ItemStack getItem(int slot) { return tank.getItem(slot); }
    @Override public void setItem(int slot, ItemStack stack) { tank.setItem(slot, stack); }
    @Override public ItemStack removeItem(int slot, int amount) { return tank.removeItem(slot, amount); }
    @Override public ItemStack removeItemNoUpdate(int slot) { return tank.removeItemNoUpdate(slot); }
    @Override public void setChanged() { tank.setChanged(); }
    @Override public boolean stillValid(Player player) { return section.master() == tank; }
    @Override public void clearContent() { tank.clearContent(); }
}
