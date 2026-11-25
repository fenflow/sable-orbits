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
        return player == pilot && !root.isRemoved() && PinMenu.inRange(player, computer) && StationAccess.locate(computer).root() == root;
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
        button(2, Items.CHAIN, "Connected stations", () -> { tab = 2; page = 0; });
        button(3, Items.PLAYER_HEAD, "Team", () -> { tab = 3; page = 0; });
        if (StationAccess.canManage(Stations.info(root).getCompound(StationAccess.KEY), pilot.getUUID()))
            button(4, Items.TRIPWIRE_HOOK, PinMenu.MANAGE, () -> PinMenu.manage(pilot, computer));
        if (tab == 3) { team(); return; }
        if (tab == 0) {
            getContainer().setItem(DRIVE, computer.getItem(0).copy());
            button(9, Items.PAPER, "Insert navigation disk in the next slot →", () -> {});
            button(19, Items.WRITABLE_BOOK, "Write station disk", this::writeDisk);
            button(20, Items.ELYTRA, "Land at disk coordinates", this::land);
            button(21, Items.FIREWORK_ROCKET, "Travel to disk planet", this::travel);
            return;
        }
        var tag = Stations.info(root);
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
                boolean direct = StationAccess.admits(Stations.info(other), pilot.getUUID());
                if (!direct && !StationAccess.configured(Stations.info(other).getCompound(StationAccess.KEY))) {
                    button(slot, Items.GRAY_DYE, name + " · Awaiting PIN setup", () -> {});
                    continue;
                }
                button(slot, Items.ENDER_PEARL, name + (direct ? " · Rendezvous" : " · Enter PIN"), () -> {
                    if (StationAccess.admits(Stations.info(other), pilot.getUUID())) result(Rendezvous.request(pilot, root, other));
                    else if (Rendezvous.available(root, other) && StationAccess.configured(Stations.info(other).getCompound(StationAccess.KEY)))
                        PinMenu.open(pilot, computer, root, other);
                    else pilot.displayClientMessage(Component.literal("This station has no PIN yet."), false);
                });
            } else {
                boolean guest = tag.hasUUID("host");
                var visit = guest ? tag : Stations.info(other);
                boolean sent = visit.getBoolean(guest ? "guestLeave" : "hostLeave");
                boolean pending = visit.getBoolean(guest ? "hostLeave" : "guestLeave");
                button(slot, Items.LEAD, name + (pending ? " · accept request to leave" : sent ? " · request sent" : " · stop rendezvous"),
                    () -> result(Rendezvous.leave(pilot,root,other)));
            }
        }
        pages(list.size());
    }
    private void team() {
        var access = Stations.info(root).getCompound(StationAccess.KEY);
        var members = access.getCompound("team");
        record Row(String name, String action, Runnable select) {}
        var rows = new ArrayList<Row>();
        if (access.hasUUID("owner")) {
            var owner = pilot.getServer().getProfileCache().get(access.getUUID("owner"));
            rows.add(new Row(owner.map(com.mojang.authlib.GameProfile::getName).orElse("Station owner"), "Owner", () -> {}));
        }
        var invited = access.getCompound("invited");
        if (invited.contains(pilot.getUUID().toString())) {
            rows.add(new Row("Team invitation", "Accept", () -> result(StationAccess.answer(pilot, root, true))));
            rows.add(new Row("Team invitation", "Decline", () -> result(StationAccess.answer(pilot, root, false))));
        }
        for (var id : members.getAllKeys()) rows.add(new Row(members.getString(id), "Member", () -> {}));
        if (Rendezvous.owner(pilot, root)) for (var candidate : Flight.crew(Flight.group(root))) {
            if (StationAccess.admits(Stations.info(root), candidate.getUUID())) continue;
            String name = candidate.getGameProfile().getName();
            if (invited.contains(candidate.getUUID().toString())) rows.add(new Row(name, "Invited", () -> {}));
            else rows.add(new Row(name, "Invite", () -> result(StationAccess.invite(pilot, root, candidate))));
        }
        rows.sort(Comparator.comparing(Row::name));
        page = Math.clamp(page, 0, Math.max(0, (rows.size()-1)/5));
        for (int i = page*5; i < Math.min(rows.size(), page*5+5); i++) {
            var row = rows.get(i);
            button(9+(i%5)*3, Items.PLAYER_HEAD, row.name + " · " + row.action, row.select);
        }
        pages(rows.size());
    }
    private void pages(int count) {
        if (page > 0) button(45, Items.ARROW, "Previous page", () -> page--);
        if ((page+1)*5 < count) button(53, Items.ARROW, "Next page", () -> page++);
    }
    private boolean related(ServerSubLevel other) {
        var tag = Stations.info(root);
        String id = other.getUniqueId().toString();
        return tag.getCompound("guests").contains(id) || (tag.hasUUID("host") && tag.getUUID("host").equals(other.getUniqueId()));
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





