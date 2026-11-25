package space.sableorbit;

import com.ntmspace.tileentity.station.TileEntityOrbitalStationComputer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;

final class PinMenu extends AbstractContainerMenu {
    static final String SETUP = "Set a PIN code";
    private final TileEntityOrbitalStationComputer computer;
    private final ServerSubLevel source, destination;
    private final UUID owner;
    private final DataSlot error = DataSlot.standalone();
    private boolean saved;
    private static final java.util.Map<ServerPlayer, Long> retryAfter = new java.util.WeakHashMap<>();

    PinMenu(int id, TileEntityOrbitalStationComputer computer, ServerSubLevel source, ServerSubLevel destination, UUID owner) {
        super(ConsoleMenus.PIN.get(), id);
        this.computer = computer;
        this.source = source;
        this.destination = destination;
        this.owner = owner;
        addDataSlot(error);
    }

    static void open(ServerPlayer player, TileEntityOrbitalStationComputer computer, ServerSubLevel source, ServerSubLevel destination) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new PinMenu(id, computer, source, destination, player.getUUID()),
            Component.literal(destination == null ? SETUP : "Enter rendezvous PIN")));
    }

    static boolean inRange(Player player, TileEntityOrbitalStationComputer computer) {
        return !computer.isRemoved() && player.level() == computer.getLevel()
            && Sable.HELPER.distanceSquaredWithSubLevels(player.level(), player.position(), computer.getBlockPos().getCenter()) < 64;
    }

    @Override public boolean stillValid(Player player) {
        if (computer == null) return true;
        return player.getUUID().equals(owner) && inRange(player, computer)
            && (source == null || !source.isRemoved() && StationAccess.locate(computer).root() == source);
    }

    boolean controls(ServerPlayer pilot, ServerSubLevel station) {
        return source == station && stillValid(pilot);
    }

    int error() { return error.get(); }

    void submit(ServerPlayer player, String pin) {
        if (player.containerMenu != this || !stillValid(player)) { player.closeContainer(); return; }
        if (!StationAccess.validPin(pin)) { error.set(1); return; }
        if (destination != null) {
            if (!Rendezvous.available(source, destination)) { error.set(3); return; }
            long now = player.getServer().overworld().getGameTime();
            if (retryAfter.getOrDefault(player, 0L) > now) { error.set(4); return; }
            if (Rendezvous.request(player, source, destination, pin)) {
                if (player.containerMenu == this) player.closeContainer();
            } else {
                retryAfter.put(player, now + 20);
                error.set(2);
            }
            return;
        }
        var location = StationAccess.locate(computer);
        var access = location.tag().getCompound(StationAccess.KEY);
        if (source != location.root() || !StationAccess.setPin(player.getServer(), access, owner, pin)) { error.set(3); return; }
        saved = true;
        computer.setChanged();
        if (source != null) {
            location.tag().remove("decay");
            Rendezvous.saveRoot(source);
            Hooks.computer(player, computer.getBlockPos());
        } else player.closeContainer();
    }

    @Override public void removed(Player player) {
        super.removed(player);
        if (saved || destination != null || computer == null || !(player instanceof ServerPlayer pilot)) return;
        if (source != null && !source.isRemoved() && !StationAccess.configured(Stations.info(source).getCompound(StationAccess.KEY)))
            Stations.warnDecay(pilot, Stations.info(source), true);
        else if (source == null)
            dev.golem.core.net.GolemNetwork.sendToPlayer(pilot, com.hbm.packet.PlayerInformPayload.CODEC,
                com.hbm.packet.PlayerInformPayload.of(7342, 1200, net.minecraft.ChatFormatting.YELLOW,
                    "Computer doesn't have a PIN-code, station will fall down in 5:00 after entering orbit"));
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    record Submit(int menu, String pin) implements CustomPacketPayload {
        static final Type<Submit> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("sableorbits", "station_pin"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Submit> CODEC = StreamCodec.of(
            (buf, value) -> { buf.writeVarInt(value.menu); buf.writeUtf(value.pin, 4); },
            buf -> new Submit(buf.readVarInt(), buf.readUtf(4)));
        public Type<Submit> type() { return TYPE; }
        static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToServer(TYPE, CODEC, (value, ctx) -> {
                if (ctx.player() instanceof ServerPlayer p && p.containerMenu instanceof PinMenu menu && menu.containerId == value.menu)
                    menu.submit(p, value.pin);
            });
        }
    }
}
