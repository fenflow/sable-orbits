package space.sableorbit;

import dev.golem.core.data.DataIn;
import dev.golem.core.data.DataOut;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

final class SolidFuselageTile extends FuselageTile {
    private int charge;

    SolidFuselageTile(BlockPos pos, BlockState state) {
        super(Fuselages.SOLID_TILE.get(), pos, state, 1);
    }

    int charge() { return charge; }
    int capacity() { return Fuselages.isSolidFuselage(part()) ? Fuselages.capacity(part()) : 0; }

    @Override public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return isMaster() && slot == 0 && SolidPropellants.units(stack) > 0;
    }

    @Override void tick() {
        if (!(level instanceof ServerLevel server) || !isMaster() || part() == null) return;
        int units = SolidPropellants.units(getItem(0));
        if (units > 0 && capacity() - charge >= units) {
            removeItem(0, 1);
            charge += units;
            setChanged();
        }
        if (canExport() && charge > 0) exportCharge(server);
    }

    private void exportCharge(ServerLevel server) {
        int original = charge;
        List<AdjacentTank> adjacentTanks = List.of();
        for (Port port : endPorts()) {
            if (charge == 0) break;
            if (!server.hasChunkAt(port.target())) continue;
            var neighbor = server.getBlockEntity(port.target());
            if (neighbor instanceof EngineTile section) {
                var engine = section.solidInlet(port.targetFace());
                if (engine != null) charge -= engine.acceptSolid(charge);
            } else if (neighbor instanceof SolidFuselageTile section) {
                if (adjacentTanks.isEmpty()) adjacentTanks = new ArrayList<>();
                adjacentTanks.add(new AdjacentTank(section, port.targetFace()));
            }
        }
        FuselageTile remainderDestination = null;
        boolean searchedRemainder = false;
        for (var adjacent : adjacentTanks) {
            if (charge == 0) break;
            if (!(adjacent.tank() instanceof SolidFuselageTile other) || other == this || !other.canReceive()) continue;
            int amount = Math.min(charge, other.capacity() - other.charge);
            if (canReceive() && other.canExport()) {
                // Equalize connected two-way magazines so the upstream charge stays available for demand at either end.
                int balance = balanceAmount(charge, capacity(), other.charge, other.capacity());
                if (balance == 0 && other.charge == 0 && amount > 0) {
                    if (!searchedRemainder) {
                        remainderDestination = remainderDestination(server, null, adjacentTanks);
                        searchedRemainder = true;
                    }
                    if (other == remainderDestination) balance = 1;
                }
                amount = Math.min(amount, balance);
            }
            if (amount <= 0) continue;
            charge -= amount;
            other.charge += amount;
            other.setChanged();
        }
        if (charge != original) setChanged();
    }

    @Override void openMenu(ServerPlayer player, BlockPos anchor) {
        if (!canUseMenu(player, anchor)) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, user) -> new SolidFuselageMenu(id, inventory, this, anchor), getDisplayName()),
            buffer -> buffer.writeVarInt(capacity()));
    }

    @Override public SolidFuselageMenu createMenu(int id, Inventory inventory, Player player) {
        return new SolidFuselageMenu(id, inventory, this, worldPosition);
    }

    @Override CompoundTag saveTankContents() {
        var tag = super.saveTankContents();
        if (charge > 0) tag.putInt("charge", charge);
        return tag;
    }

    @Override void loadTankContents(CompoundTag tag) {
        super.loadTankContents(tag);
        charge = Math.clamp(tag.getInt("charge"), 0, capacity());
    }

    @Override protected void saveData(DataOut data) {
        super.saveData(data);
        if (charge > 0) data.putInt("charge", charge);
    }

    @Override protected void loadData(DataIn data) {
        super.loadData(data);
        charge = Math.clamp(data.getIntOr("charge", 0), 0, capacity());
    }
}
