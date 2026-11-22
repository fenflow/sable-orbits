package space.sableorbit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class Flight {
    private record Arrival(UUID part, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                           Vec3 feet, int expires) {}
    private static final Map<ServerPlayer,Arrival> arrivals = new WeakHashMap<>();

    // A standing rider can start falling before the client has built the new collision mesh.
    // Preserve the local contact point during this short handover. Walking off the deck releases it.
    static void settleArrivals(ServerLevel level) {
        for (var player : level.players()) {
            var arrival = arrivals.get(player);
            if (arrival == null) continue;
            if (arrival.dimension() != level.dimension() || player.tickCount > arrival.expires() || player.isPassenger()) {
                arrivals.remove(player);
                continue;
            }
            var container = SubLevelContainer.getContainer(level);
            if (container == null || !(container.getSubLevel(arrival.part()) instanceof ServerSubLevel part)) {
                arrivals.remove(player);
                continue;
            }
            var local = part.logicalPose().transformPositionInverse(player.position());
            var feet = arrival.feet();
            if (Math.hypot(local.x-feet.x,local.z-feet.z) > 0.9 || local.y > feet.y+0.6) {
                arrivals.remove(player);
            } else if (local.y < feet.y-0.08) {
                var dest = part.logicalPose().transformPosition(feet);
                player.connection.teleport(dest.x,dest.y+0.01,dest.z,player.getYRot(),player.getXRot());
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0;
            }
        }
    }

    private static final boolean WIRES = net.neoforged.fml.ModList.get().isLoaded("drivebywire");
    static List<ServerSubLevel> group(ServerSubLevel root) { return group(root,false); }
    static List<ServerSubLevel> group(ServerSubLevel root,boolean crossStationLinks) {
        var result = new LinkedHashSet<ServerSubLevel>();
        var pending = new ArrayDeque<ServerSubLevel>();
        var info = Stations.info(root);
        var wires = WIRES ? ShipWires.links(root.getLevel()) : java.util.List.<ShipWires.Link>of();
        pending.add(root);
        while (!pending.isEmpty()) {
            var part = pending.removeFirst();
            if (result.contains(part)) continue;
            var candidate = Stations.info(part);
            if (!crossStationLinks && info.hasUUID("root") && candidate.hasUUID("root")
                    && !info.getUUID("root").equals(candidate.getUUID("root"))) continue;
            result.add(part);
            if (WIRES) ShipWires.neighbors(part,pending,wires);
            for (var dependency : SubLevelHelper.getLoadingDependencyChain(part))
                if (!result.contains(dependency)) pending.addLast(dependency);
            for (var holder : part.getPlot().getLoadedChunks())
                for (var tile : holder.getChunk().getBlockEntities().values())
                    if (tile instanceof com.hbm.tileentity.network.TileEntityPylonBase pylon)
                        for (var peer : pylon.getConnected()) {
                            if (Sable.HELPER.getContaining(part.getLevel(),peer) instanceof ServerSubLevel other
                                    && !result.contains(other)) pending.addLast(other);
                        }
        }
        return List.copyOf(result);
    }

    static List<ServerPlayer> crew(List<ServerSubLevel> parts) {
        var members = new HashSet<>(parts);
        return parts.getFirst().getLevel().players().stream().filter(ServerPlayer::isAlive)
            .filter(p -> members.contains(Sable.HELPER.getTrackingOrVehicleSubLevel(p))
                || onDeck(p,parts,OrbitSpaces.isolated(p.level()) ? 2.1 : 0.12)).toList();
    }

    // Keep the original plot addresses. Block entities and joints can store absolute plot positions.
    static List<ServerSubLevel> transfer(List<ServerSubLevel> parts, ServerLevel target, Vec3 offset) {
        var origin = parts.getFirst().getLevel();
        var destination = SubLevelContainer.getContainer(target);
        var source = SubLevelContainer.getContainer(origin);
        if (destination == null || source == null || !destination.getOrigin().equals(source.getOrigin())
                || destination.getLogPlotSize() != source.getLogPlotSize()) return List.of();
        for (var part : parts) {
            var plot = part.getPlot().plotPos;
            int index = destination.getIndex(plot.x - source.getOrigin().x, plot.z - source.getOrigin().y);
            if (destination.getOccupancy().get(index) || destination.getSubLevel(part.getUniqueId()) != null)
                return List.of();
        }
        var members = new HashSet<>(parts);
        var wireLinks = WIRES ? ShipWires.onboard(origin,members) : java.util.List.<ShipWires.Link>of();
        var originals = new LinkedHashSet<Entity>();
        for (var entity : origin.getAllEntities()) {
            if (members.contains(Sable.HELPER.getContaining(entity))
                    || members.contains(Sable.HELPER.getTrackingOrVehicleSubLevel(entity))
                    || (entity instanceof ServerPlayer p && p.containerMenu instanceof Console menu && menu.controls(members))
                    || (entity instanceof ServerPlayer p && onDeck(p,parts,OrbitSpaces.isolated(origin) ? 2.1 : 0.12))
                    || (entity.getPersistentData().hasUUID("sableorbits_berth") && parts.stream().anyMatch(p -> p.getUniqueId().equals(entity.getPersistentData().getUUID("sableorbits_berth"))))) {
                var root = entity.getRootVehicle();
                originals.add(root);
                root.getIndirectPassengers().forEach(originals::add);
            }
        }
        var vehicles = new HashMap<Entity,Entity>();
        var positions = new HashMap<Entity,Vec3>();
        var replacements = new LinkedHashMap<Entity,Entity>();
        for (var entity : originals) {
            if (target.getEntity(entity.getUUID()) != null) return List.of();
            vehicles.put(entity, entity.getVehicle());
            boolean local = members.contains(Sable.HELPER.getContaining(entity));
            var pos = local && !(entity instanceof ServerPlayer) ? entity.position()
                : Sable.HELPER.projectOutOfSubLevel(origin, entity.position()).add(offset);
            positions.put(entity, pos);
            if (entity instanceof ServerPlayer) continue;
            var copy = entity.getType().create(target);
            if (copy == null) return List.of();
            var tag = entity.saveWithoutId(new CompoundTag());
            tag.remove("Passengers");
            copy.load(tag);
            copy.moveTo(pos.x, pos.y, pos.z, entity.getYRot(), entity.getXRot());
            replacements.put(entity, copy);
        }

        var loaded = new ArrayList<ServerSubLevel>();
        var inserted = new ArrayList<Entity>();
        try {
            var ids = parts.stream().map(ServerSubLevel::getUniqueId).toList();
            for (var part : parts) {
                var tag = SubLevelSerializer.toData(part, ids).fullTag();
                adaptSections(tag.getCompound("plot"),origin,target);
                translateRopes(tag.getCompound("plot"),offset);
                var pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));
                pose.position().add(offset.x, offset.y, offset.z);
                tag.put("pose", SableNBTUtils.writePose3d(pose));
                tag.remove("linear_velocity");
                tag.remove("angular_velocity");
                target.getChunk((int)Math.floor(pose.position().x / 16), (int)Math.floor(pose.position().z / 16));
                var copy = SubLevelSerializer.fullyLoad(target, SubLevelSerializer.fromData(tag));
                if (copy == null) throw new IllegalStateException("Sable rejected destination plot");
                for (var holder : part.getPlot().getLoadedChunks()) {
                    for (var entry : holder.getChunk().getBlockEntities().entrySet()) {
                        var pos = entry.getKey();
                        var restored = target.getBlockEntity(pos);
                        if (restored == null || restored.getType() != entry.getValue().getType()
                                || target.getBlockState(pos) != origin.getBlockState(pos))
                            throw new IllegalStateException("Incomplete block entity transfer at " + pos);
                    }
                }
                loaded.add(copy);
            }
            if (WIRES) ShipWires.write(target,wireLinks);
            for (var entity : replacements.values()) {
                if (!target.addFreshEntity(entity)) throw new IllegalStateException("Entity transfer rejected");
                inserted.add(entity);
            }
        } catch (RuntimeException ex) {
            if (WIRES) ShipWires.clear(target,wireLinks);
            inserted.forEach(Entity::discard);
            for (var part : parts) {
                var copy = destination.getSubLevel(part.getUniqueId());
                if (copy != null) destination.removeSubLevel(copy, SubLevelRemovalReason.REMOVED);
            }
            Sable.LOGGER.error("Station transfer could not stage its destination", ex);
            return List.of();
        }
        var contacts = new HashMap<ServerPlayer,Arrival>();
        for (var entity : originals) {
            if (!(entity instanceof ServerPlayer player) || player.isPassenger()) continue;
            for (var part : parts) {
                if (!onDeck(player,List.of(part),0.3)) continue;
                contacts.put(player,new Arrival(part.getUniqueId(),target.dimension(),
                    part.logicalPose().transformPositionInverse(player.position()),player.tickCount+100));
                break;
            }
        }
        originals.forEach(Entity::stopRiding);
        for (var entity : originals) {
            if (entity instanceof ServerPlayer player) {
                var pos = positions.get(player);
                player.teleportTo(target, pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
                player.fallDistance = 0;
                player.setDeltaMovement(Vec3.ZERO);
                arrivals.remove(player);
                if (contacts.containsKey(player)) arrivals.put(player,contacts.get(player));
                replacements.put(player, player);
            } else entity.discard();
        }
        vehicles.forEach((rider, mount) -> {
            if (mount != null) replacements.get(rider).startRiding(replacements.get(mount), true);
        });
        if (WIRES) ShipWires.clear(origin,wireLinks);
        for (var part : parts) source.removeSubLevel(part, SubLevelRemovalReason.REMOVED);
        for(var copy:loaded) {
            Stations.track(copy);
            StationLife.remember(copy,Stations.info(copy));
        }
        return loaded;
    }
    private static boolean onDeck(ServerPlayer player,List<ServerSubLevel> parts,double tolerance) {
        for (var part : parts) {
            var feet = part.logicalPose().transformPositionInverse(player.position());
            for (int y=(int)Math.floor(feet.y);y>=(int)Math.floor(feet.y-tolerance);y--) {
                var pos = net.minecraft.core.BlockPos.containing(feet.x,y,feet.z);
                if (!part.getPlot().contains(new net.minecraft.world.level.ChunkPos(pos))) continue;
                var shape = part.getLevel().getBlockState(pos).getCollisionShape(part.getLevel(),pos);
                for (var box : shape.toAabbs()) {
                    double gap = feet.y - (y + box.maxY);
                    if (gap >= -0.04 && gap <= tolerance && feet.x-pos.getX() >= box.minX && feet.x-pos.getX() <= box.maxX
                            && feet.z-pos.getZ() >= box.minZ && feet.z-pos.getZ() <= box.maxZ) return true;
                }
            }
        }
        return false;
    }
    private static void translateRopes(CompoundTag plot,Vec3 offset) {
        var chunks = plot.getCompound("chunks");
        for (var key : chunks.getAllKeys()) for (var data : chunks.getCompound(key).getList("block_entities",10)) {
            var tile = (CompoundTag)data;
            if (!Set.of("simulated:rope_connector","simulated:rope_winch").contains(tile.getString("id")) || !tile.contains("Strand")) continue;
            // Simulated stores the attachment addresses in plot space and the free rope segments in world space.
            var points = tile.getCompound("Strand").getList("points",9);
            for (var entry : points) {
                var xyz = (net.minecraft.nbt.ListTag)entry;
                xyz.set(0,net.minecraft.nbt.DoubleTag.valueOf(xyz.getDouble(0)+offset.x));
                xyz.set(1,net.minecraft.nbt.DoubleTag.valueOf(xyz.getDouble(1)+offset.y));
                xyz.set(2,net.minecraft.nbt.DoubleTag.valueOf(xyz.getDouble(2)+offset.z));
            }
        }
    }
    private static void adaptSections(CompoundTag plot,ServerLevel origin,ServerLevel target) {
        if (origin.getMinBuildHeight()==target.getMinBuildHeight() && origin.getHeight()==target.getHeight()) return;
        var chunks = plot.getCompound("chunks");
        for (String key : chunks.getAllKeys()) {
            var chunk = chunks.getCompound(key);
            var sections = chunk.getCompound("sections");
            var mapped = new CompoundTag();
            for (String index : sections.getAllKeys()) {
                int y = origin.getSectionYFromSectionIndex(Integer.parseInt(index));
                int dest = target.getSectionIndexFromSectionY(y);
                if (dest < 0 || dest >= target.getSectionsCount()) throw new IllegalStateException("Hull exceeds destination plot height");
                mapped.put(Integer.toString(dest), sections.get(index));
            }
            chunk.put("sections",mapped);
            // Heightmap values are also encoded relative to the dimension's lower bound.
            chunk.remove("heightmaps");
        }
    }
}
