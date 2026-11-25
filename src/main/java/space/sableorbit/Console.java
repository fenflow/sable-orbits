package space.sableorbit;

import com.ntmspace.orbit.*;
import com.ntmspace.rocket.ItemVOTVdrive;
import com.ntmspace.tileentity.station.TileEntityOrbitalStationComputer;
import com.ntmspace.world.SolarSystem;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import java.util.*;

final class Console extends ChestMenu {
    private final ServerPlayer pilot;
    private final ServerSubLevel root;
    private final TileEntityOrbitalStationComputer computer;
    private final Map<Integer,Runnable> buttons = new HashMap<>();
    private int tab, page;
    private static final int DRIVE = 10;
    Console(int id, ServerPlayer player, ServerSubLevel root, TileEntityOrbitalStationComputer computer) {
        super(ConsoleMenus.TYPE.get(), id, player.getInventory(), new SimpleContainer(54), 6);
        this.pilot = player;
        this.root = root;
        this.computer = computer;
        refresh();
    }
    @Override public boolean stillValid(Player player) {
        return !root.isRemoved() && player.level() == root.getLevel() && !computer.isRemoved()
            && Sable.HELPER.distanceSquaredWithSubLevels(player.level(), player.position(), computer.getBlockPos().getCenter()) < 64;
    }
    private void button(int slot, Item icon, String label, Runnable action) {
        var stack = new ItemStack(icon);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        getContainer().setItem(slot, stack);
        buttons.put(slot, action);
    }
    private void refresh() {
        getContainer().clearContent();
        buttons.clear();
        var station = Stations.nativeStation(root);
        if (station == null) return;
        button(0, Items.COMPASS, "Flight · " + station.orbiting.name, () -> { tab = 0; page = 0; });
        button(1, Items.SPYGLASS, "Stations around " + station.orbiting.name, () -> { tab = 1; page = 0; });
        button(2, Items.CHAIN, "Rendezvous requests and connected stations", () -> { tab = 2; page = 0; });
        if (tab == 0) {
            getContainer().setItem(DRIVE, computer.getItem(0).copy());
            button(9, Items.PAPER, "Insert navigation disk in the next slot →", () -> {});
            button(19, Items.WRITABLE_BOOK, "Write station disk", this::writeDisk);
            button(20, Items.ELYTRA, "Land at disk coordinates", this::land);
            button(21, Items.FIREWORK_ROCKET, "Travel to disk planet", this::travel);
            return;
        }
        var tag = Stations.info(root);
        button(8, Items.REDSTONE_TORCH, tag.getBoolean("public") ? "Public · make private" : "Private · make public", () -> {
            if (Rendezvous.owner(pilot, root)) tag.putBoolean("public", !tag.getBoolean("public"));
        });
        var list = Rendezvous.stations(pilot.getServer()).stream().filter(s -> s != root)
            .filter(s -> {
                var target = Stations.nativeStation(s);
                return station.state == OrbitalStation.StationState.ORBIT && target != null
                    && target.state == OrbitalStation.StationState.ORBIT && target.orbiting == station.orbiting;
            })
            .filter(s -> tab == 1 || related(s))
            .sorted(Comparator.comparing(ServerSubLevel::getUniqueId)).toList();
        page = Math.clamp(page, 0, Math.max(0, (list.size()-1)/5));
        for (int i = page*5; i < Math.min(list.size(), page*5+5); i++) {
            var other = list.get(i);
            int slot = 9 + (i%5)*3;
            String name = Stations.nativeStation(other).name;
            if (tab == 1) {
                if (!Rendezvous.available(root,other)) {
                    button(slot, Items.GRAY_DYE, name + " · unavailable", () -> {});
                    continue;
                }
                boolean sent = Stations.info(other).getCompound("requests").contains(root.getUniqueId().toString());
                button(slot, Items.ENDER_PEARL, name + (sent ? " · request sent" : " · rendezvous"), () -> result(Rendezvous.request(pilot,root,other)));
            } else if (tag.getCompound("requests").contains(other.getUniqueId().toString()) && Rendezvous.available(other,root)) {
                button(slot, Items.LIME_DYE, name + " · accept rendezvous", () -> result(Rendezvous.decide(pilot,root,other,true)));
                button(slot+1, Items.RED_DYE, name + " · deny", () -> result(Rendezvous.decide(pilot,root,other,false)));
            } else {
                boolean guest = tag.hasUUID("host");
                var visit = guest ? tag : Stations.info(other);
                boolean sent = visit.getBoolean(guest ? "guestLeave" : "hostLeave");
                boolean pending = visit.getBoolean(guest ? "hostLeave" : "guestLeave");
                button(slot, Items.LEAD, name + (pending ? " · accept request to leave" : sent ? " · request sent" : " · stop rendezvous"),
                    () -> result(Rendezvous.leave(pilot,root,other)));
            }
        }
        if (page > 0) button(45, Items.ARROW, "Previous page", () -> page--);
        if ((page+1)*5 < list.size()) button(53, Items.ARROW, "Next page", () -> page++);
    }
    private boolean related(ServerSubLevel other) {
        var tag = Stations.info(root);
        String id = other.getUniqueId().toString();
        return tag.getCompound("guests").contains(id) || (tag.hasUUID("host") && tag.getUUID("host").equals(other.getUniqueId()))
            || (tag.getCompound("requests").contains(id) && Rendezvous.available(other,root));
    }
    boolean controls(Set<ServerSubLevel> parts) { return parts.contains(root) && stillValid(pilot); }
    private void result(boolean ok) {
        if (!ok) pilot.displayClientMessage(Component.literal("Station operation unavailable. Check ownership, orbit, drive and destination."), false);
    }
    private void writeDisk() {
        var disk = computer.getItem(0);
        if (!BuiltInRegistries.ITEM.getKey(disk.getItem()).toString().equals("ntmspace:hard_drive") || disk.getCount() != 1) { result(false); return; }
        var station = Stations.nativeStation(root);
        var output = NavDrives.FACTORIES.invoker().create(station.dX, station.dZ, station.name);
        if (!output.isEmpty()) { computer.setItem(0, output); computer.setChanged(); }
    }
    private void land() {
        var disk = computer.getItem(0);
        var dest = NavDrives.destination(disk);
        var station = Stations.nativeStation(root);
        if (dest == null || dest.isOrbit() || !ItemVOTVdrive.getProcessed(disk) || !station.orbiting.name.equalsIgnoreCase(dest.body())) { result(false); return; }
        result(Stations.land(root, dest.x(), dest.z()));
    }
    private void travel() {
        var disk = computer.getItem(0);
        var dest = NavDrives.destination(disk);
        if (dest == null || dest.isOrbit() || !ItemVOTVdrive.getProcessed(disk) || Rendezvous.linked(Stations.info(root))) { result(false); return; }
        var station = Stations.nativeStation(root);
        var body = SolarSystem.body(dest.body());
        if (body == null || body == station.orbiting) { result(false); return; }
        station.travelTo(root.getLevel(), body);
        result(station.state != OrbitalStation.StationState.ORBIT);
    }
    @Override public void clicked(int index, int mouse, ClickType type, Player player) {
        if (!stillValid(player)) { player.closeContainer(); return; }
        if (type == ClickType.QUICK_CRAFT || type == ClickType.PICKUP_ALL || type == ClickType.QUICK_MOVE) return;
        if (index >= 0 && index < 54) {
            if (type != ClickType.PICKUP) return;
            if (index == DRIVE && tab == 0) {
                var held = getCarried();
                if (!held.isEmpty() && (!(held.getItem() instanceof ItemVOTVdrive) && !BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals("ntmspace:hard_drive") || held.getCount() != 1)) return;
                var old = computer.getItem(0);
                computer.setItem(0, held);
                setCarried(old);
                computer.setChanged();
            } else {
                var action = buttons.get(index);
                if (action != null) action.run();
            }
        } else super.clicked(index, mouse, type, player);
        if (stillValid(player)) refresh();
        else player.closeContainer();
    }
    @Override public boolean clickMenuButton(Player player,int id) {
        if (player != pilot || !stillValid(player)) return false;
        var action = buttons.get(id);
        if (action == null) return false;
        action.run();
        if (stillValid(player)) refresh();
        else player.closeContainer();
        return true;
    }
    private int refreshTicks;
    @Override public void broadcastChanges() {
        if (++refreshTicks >= 20 && stillValid(pilot)) { refreshTicks = 0; refresh(); }
        super.broadcastChanges();
    }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
}





