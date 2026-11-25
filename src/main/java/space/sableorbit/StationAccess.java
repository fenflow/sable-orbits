package space.sableorbit;

import com.ntmspace.tileentity.station.TileEntityOrbitalStationComputer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;

final class StationAccess {
    static final String KEY = "stationAccess";
    private static final String PREFLIGHT = "sableorbits_preflight";
    private static final String LANDED = "sableorbits_landed";

    static boolean validPin(String pin) {
        return pin.length() == 4 && pin.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    static CompoundTag claim(CompoundTag tag, UUID owner, long tick) {
        var access = tag.getCompound(KEY);
        if (!access.hasUUID("owner")) {
            access.putUUID("owner", owner);
            access.putLong("placed", tick);
            tag.put(KEY, access);
        }
        tag.putUUID("owner", access.getUUID("owner"));
        return access;
    }

    // Hull tags can travel to clients. Keep the key for checking a guess in overworld SavedData.
    private static byte[] proof(MinecraftServer server, UUID salt, String pin) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(OrbitSpaces.data(server).pinKey(), "HmacSHA256"));
            return mac.doFinal((salt.toString() + pin).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean setPin(MinecraftServer server, CompoundTag access, UUID actor, String pin) {
        if (!validPin(pin) || !access.hasUUID("owner") || !access.getUUID("owner").equals(actor) || configured(access)) return false;
        writePin(server, access, pin);
        return true;
    }

    static boolean changePin(MinecraftServer server, CompoundTag access, UUID actor, UUID displayedSalt, String pin) {
        if (!validPin(pin) || !canManage(access, actor) || !access.getUUID("salt").equals(displayedSalt)) return false;
        writePin(server, access, pin);
        return true;
    }

    static boolean canManage(CompoundTag access, UUID actor) {
        return configured(access) && access.hasUUID("owner") && access.getUUID("owner").equals(actor);
    }

    private static Cipher ownerCipher(MinecraftServer server, UUID salt, int mode) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(OrbitSpaces.data(server).pinKey(), "HmacSHA256"));
        var key = mac.doFinal("sableorbits:owner-pin:v1".getBytes(StandardCharsets.US_ASCII));
        var nonce = ByteBuffer.allocate(12).putLong(salt.getMostSignificantBits()).putInt((int)salt.getLeastSignificantBits()).array();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher;
    }

    private static void writePin(MinecraftServer server, CompoundTag access, String pin) {
        var salt = UUID.randomUUID();
        var verification = proof(server, salt, pin);
        sealPin(server, access, salt, pin);
        access.putUUID("salt", salt);
        access.putByteArray("proof", verification);
    }

    private static void sealPin(MinecraftServer server, CompoundTag access, UUID salt, String pin) {
        try {
            access.putByteArray("ownerPin", ownerCipher(server, salt, Cipher.ENCRYPT_MODE).doFinal(pin.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static String ownerPin(MinecraftServer server, CompoundTag access, UUID actor) {
        if (!canManage(access, actor)) return "";
        var sealed = access.getByteArray("ownerPin");
        if (sealed.length != 20) return "";
        try {
            var digits = ownerCipher(server, access.getUUID("salt"), Cipher.DECRYPT_MODE).doFinal(sealed);
            return new String(digits, StandardCharsets.US_ASCII);
        } catch (AEADBadTagException e) {
            return "";
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static void adoptComputer(ServerSubLevel root, List<ServerSubLevel> parts) {
        var tag = Stations.info(root);
        if (tag.getCompound(KEY).hasUUID("owner")) return;
        var placement = earliest(parts);
        if (placement != null && placement.hasUUID("owner")) {
            tag.put(KEY, placement.copy());
            tag.putUUID("owner", placement.getUUID("owner"));
        } else if (tag.hasUUID("owner")) {
            claim(tag, tag.getUUID("owner"), root.getLevel().getGameTime());
        } else {
            Rendezvous.entries(tag, KEY);
        }
        Rendezvous.saveRoot(root);
    }

    static boolean configured(CompoundTag access) { return access.hasUUID("salt"); }

    static boolean admits(CompoundTag tag, UUID actor) {
        var access = tag.getCompound(KEY);
        return !tag.contains(KEY) || access.hasUUID("owner") && actor.equals(access.getUUID("owner"))
            || access.getCompound("team").contains(actor.toString());
    }

    static boolean matches(MinecraftServer server, CompoundTag tag, String pin) {
        var access = tag.getCompound(KEY);
        return validPin(pin) && configured(access) && MessageDigest.isEqual(access.getByteArray("proof"),
            proof(server, access.getUUID("salt"), pin));
    }

    static boolean verifyVisitPin(MinecraftServer server, CompoundTag tag, String pin) {
        if (!matches(server, tag, pin)) return false;
        var access = tag.getCompound(KEY);
        // Proof-only saves can acquire the owner display once a visitor supplies the correct PIN.
        if (!access.contains("ownerPin")) sealPin(server, access, access.getUUID("salt"), pin);
        return true;
    }

    // Before first ascent, assembly can combine several computers and plots. The oldest claim wins.
    static CompoundTag earliest(List<ServerSubLevel> parts) {
        CompoundTag first = null;
        for (var part : parts) {
            var data = part.getUserDataTag();
            if (data != null) first = older(first, data.getCompound(PREFLIGHT));
            for (var holder : part.getPlot().getLoadedChunks())
                for (var tile : holder.getChunk().getBlockEntities().values())
                    if (tile instanceof TileEntityOrbitalStationComputer) first = older(first, tile.getPersistentData().getCompound(PREFLIGHT));
        }
        return first;
    }

    static void clearPreflight(ServerSubLevel part) {
        var data = part.getUserDataTag();
        if (data != null) data.remove(PREFLIGHT);
        for (var holder : part.getPlot().getLoadedChunks())
            for (var tile : holder.getChunk().getBlockEntities().values())
                if (tile instanceof TileEntityOrbitalStationComputer && tile.getPersistentData().contains(PREFLIGHT)) {
                    tile.getPersistentData().remove(PREFLIGHT);
                    tile.setChanged();
                }
    }

    static CompoundTag placement(TileEntityOrbitalStationComputer computer) {
        return computer.getPersistentData().getCompound(PREFLIGHT).getCompound(KEY);
    }

    private static CompoundTag older(CompoundTag first, CompoundTag container) {
        if (!container.contains(KEY)) return first;
        var next = container.getCompound(KEY);
        return first == null || next.getLong("placed") < first.getLong("placed") ? next : first;
    }

    record Location(CompoundTag tag, ServerSubLevel root) {}

    static Location locate(TileEntityOrbitalStationComputer computer) {
        var level = computer.getLevel();
        if (!(Sable.HELPER.getContaining(level, computer.getBlockPos()) instanceof ServerSubLevel part))
            return new Location(Rendezvous.entries(computer.getPersistentData(), PREFLIGHT), null);
        var root = Stations.root(part);
        if (root != null) return new Location(Stations.info(root), root);
        var data = part.getUserDataTag();
        if (data != null && data.getCompound(LANDED).hasUUID("root")) {
            var landed = SubLevelContainer.getContainer(level).getSubLevel(data.getCompound(LANDED).getUUID("root"));
            if (landed instanceof ServerSubLevel hull && hull.getUserDataTag() != null)
                return new Location(hull.getUserDataTag().getCompound(LANDED), null);
        }
        var group = Flight.group(part);
        for (var member : group) {
            root = Stations.root(member);
            if (root != null) return new Location(Stations.info(root), root);
            var memberData = member.getUserDataTag();
            if (memberData != null && memberData.getCompound(LANDED).hasUUID("root")
                    && member.getUniqueId().equals(memberData.getCompound(LANDED).getUUID("root")))
                return new Location(memberData.getCompound(LANDED), null);
        }
        var first = earliest(group);
        if (first != null) for (var member : group) {
            var memberData = member.getUserDataTag();
            if (memberData != null && memberData.getCompound(PREFLIGHT).get(KEY) == first)
                return new Location(memberData.getCompound(PREFLIGHT), null);
        }
        if (data == null) { data = new CompoundTag(); part.setUserDataTag(data); }
        var preflight = Rendezvous.entries(data, PREFLIGHT);
        if (first != null) preflight.put(KEY, first);
        return new Location(preflight, null);
    }

    static void placed(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var level = player.serverLevel();
        var snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi ? multi.getReplacedBlockSnapshots()
            : List.of(event.getBlockSnapshot());
        for (var snapshot : snapshots) {
            if (!(level.getBlockEntity(snapshot.getPos()) instanceof TileEntityOrbitalStationComputer computer)) continue;
            // Keep the original placer on the staged core. A transfer or use can run before the menu opens.
            claim(Rendezvous.entries(computer.getPersistentData(), PREFLIGHT), player.getUUID(), level.getServer().overworld().getGameTime());
            computer.setChanged();
            // NeoForge rolls back canceled placements after the event. Verify the committed core first.
            level.getServer().tell(new net.minecraft.server.TickTask(level.getServer().getTickCount(), () -> {
                if (!event.isCanceled() && level.getBlockEntity(computer.getBlockPos()) == computer) {
                    var location = locate(computer);
                    claim(location.tag, player.getUUID(), level.getServer().overworld().getGameTime());
                    computer.setChanged();
                    if (location.root != null) {
                        computer.getPersistentData().remove(PREFLIGHT);
                        Rendezvous.saveRoot(location.root);
                    }
                    if (PinMenu.inRange(player, computer) && !configured(location.tag.getCompound(KEY)))
                        Hooks.computer(player, computer.getBlockPos());
                }
            }));
            break;
        }
    }

    static boolean invite(ServerPlayer owner, ServerSubLevel root, ServerPlayer recruit) {
        var access = Stations.info(root).getCompound(KEY);
        var station = Stations.nativeStation(root);
        if (root.isRemoved() || Stations.root(root) != root || station == null || !access.hasUUID("owner")
                || !owner.getUUID().equals(access.getUUID("owner")) || owner == recruit
                || !Flight.crew(Flight.group(root)).contains(recruit)) return false;
        String id = recruit.getUUID().toString();
        if (access.getCompound("team").contains(id) || access.getCompound("invited").contains(id)) return false;
        Rendezvous.entries(access, "invited").putBoolean(id, true);
        recruit.displayClientMessage(net.minecraft.network.chat.Component.literal("You were invited to the team of "
            + station.name + ". Open Team on its station computer to respond."), false);
        Rendezvous.saveRoot(root);
        return true;
    }

    static boolean answer(ServerPlayer recruit, ServerSubLevel root, boolean accept) {
        var access = Stations.info(root).getCompound(KEY);
        String id = recruit.getUUID().toString();
        var invited = access.getCompound("invited");
        if (root.isRemoved() || Stations.root(root) != root || !invited.contains(id)
                || !Flight.crew(Flight.group(root)).contains(recruit)) return false;
        invited.remove(id);
        if (accept) Rendezvous.entries(access, "team").putString(id, recruit.getGameProfile().getName());
        Rendezvous.saveRoot(root);
        return true;
    }
}
