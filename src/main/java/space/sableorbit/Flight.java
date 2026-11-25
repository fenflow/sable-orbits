package space.sableorbit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import space.sableorbit.mixin.TransferChunkAccess;
import java.util.*;
import java.util.function.Consumer;

final class Flight {
    private record Arrival(UUID part, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                           Vec3 feet, int expires) {}
    private static final Map<ServerPlayer,Arrival> arrivals = new WeakHashMap<>();

    private record PlayerPosition(Vec3 position, Vec3 velocity, float yaw, float pitch, float fallDistance,
                                  SubLevel trackingDeck, Spacewalk.Reference spacewalk, org.joml.Quaternionf orientation) {
        static PlayerPosition of(ServerPlayer player) {
            return new PlayerPosition(player.position(), player.getDeltaMovement(), player.getYRot(),
                player.getXRot(), player.fallDistance, Sable.HELPER.getTrackingSubLevel(player),
                Spacewalk.reference(player), Attitude.savedOrientation(player));
        }

        void restore(ServerPlayer player, ServerLevel origin) {
            if (player.level() != origin) {
                FlightRollback.returnPlayer(player, origin, position, yaw, pitch);
                if (player.level() != origin || origin.getEntity(player.getUUID()) != player)
                    throw new IllegalStateException("Could not return station passenger " + player.getUUID());
            } else {
                player.connection.teleport(position.x, position.y, position.z, yaw, pitch);
            }
            player.setDeltaMovement(velocity);
            player.fallDistance = fallDistance;
            ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension) player)
                .sable$setTrackingSubLevel(trackingDeck);
            Spacewalk.restore(player, spacewalk);
            Attitude.restoreOrientation(player, orientation);
        }
    }

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
    static boolean wiresLoaded() { return WIRES; }
    static List<ServerSubLevel> group(ServerSubLevel root) { return group(root,false); }
    static List<ServerSubLevel> group(ServerSubLevel root,boolean crossStationLinks) {
        return group(root, crossStationLinks, new FlightTopology(root.getLevel()));
    }

    static List<ServerSubLevel> group(ServerSubLevel root, boolean crossStationLinks, FlightTopology topology) {
        var level = root.getLevel();
        var result = new ArrayList<ServerSubLevel>();
        var pending = new ArrayDeque<ServerSubLevel>();
        var seen = new HashSet<ServerSubLevel>();
        var info = Stations.info(root);
        var station = !crossStationLinks && info.hasUUID("root") ? info.getUUID("root") : null;
        Consumer<SubLevel> enqueue = neighbor -> {
            if (neighbor instanceof ServerSubLevel part && part.getLevel() == level && !part.isRemoved() && seen.add(part)) {
                pending.addLast(part);
            }
        };
        pending.add(root);
        seen.add(root);
        while (!pending.isEmpty()) {
            var part = pending.removeFirst();
            if (station != null) {
                var candidate = Stations.info(part);
                if (candidate.hasUUID("root") && !station.equals(candidate.getUUID("root"))) continue;
            }
            result.add(part);
            // Sable's loading chain follows intersections and actor loading dependencies.
            // Visit those edges here so a different station root also stops traversal.
            topology.neighbors(part).forEach(enqueue);
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
        return transfer(parts, target, offset, List.of());
    }

    static List<ServerSubLevel> transfer(List<ServerSubLevel> parts, ServerLevel target, Vec3 offset,
                                        Collection<ServerPlayer> standingBeforeAssembly) {
        var origin = parts.getFirst().getLevel();
        var destination = SubLevelContainer.getContainer(target);
        var source = SubLevelContainer.getContainer(origin);
        if (target == origin || destination == null || source == null || !destination.getOrigin().equals(source.getOrigin())
                || destination.getLogPlotSize() != source.getLogPlotSize()) return List.of();
        for (var part : parts) {
            var plot = part.getPlot().plotPos;
            int index = destination.getIndex(plot.x - source.getOrigin().x, plot.z - source.getOrigin().y);
            if (destination.getOccupancy().get(index) || destination.getSubLevel(part.getUniqueId()) != null)
                return List.of();
        }
        var members = new HashSet<>(parts);
        var ids = parts.stream().map(ServerSubLevel::getUniqueId).toList();
        var memberIds = Set.copyOf(ids);
        var standing = Set.copyOf(standingBeforeAssembly);
        double standingTolerance = OrbitSpaces.isolated(origin) ? 2.1 : 0.12;
        var wireLinks = WIRES ? ShipWires.onboard(origin,members) : java.util.List.<ShipWires.Link>of();
        var originals = new LinkedHashSet<Entity>();
        for (var entity : origin.getAllEntities()) {
            if (members.contains(Sable.HELPER.getContaining(entity))
                    || members.contains(Sable.HELPER.getTrackingOrVehicleSubLevel(entity))
                    || standing.contains(entity)
                    || (entity instanceof ServerPlayer p && p.containerMenu instanceof Console menu && menu.controls(members))
                    || (entity instanceof ServerPlayer p && onDeck(p,parts,standingTolerance))
                    || (entity.getPersistentData().hasUUID("sableorbits_berth") && memberIds.contains(entity.getPersistentData().getUUID("sableorbits_berth")))) {
                var root = entity.getRootVehicle();
                if (originals.add(root)) root.getIndirectPassengers().forEach(originals::add);
            }
        }
        var vehicles = new LinkedHashMap<Entity,Entity>();
        var positions = new HashMap<Entity,Vec3>();
        var replacements = new LinkedHashMap<Entity,Entity>();
        var players = new LinkedHashMap<ServerPlayer,PlayerPosition>();
        for (var entity : originals) {
            if (target.getEntity(entity.getUUID()) != null) return List.of();
            vehicles.put(entity, entity.getVehicle());
            boolean local = members.contains(Sable.HELPER.getContaining(entity));
            var pos = local && !(entity instanceof ServerPlayer) ? entity.position()
                : Sable.HELPER.projectOutOfSubLevel(origin, entity.position()).add(offset);
            positions.put(entity, pos);
            if (entity instanceof ServerPlayer player) {
                players.put(player, PlayerPosition.of(player));
                continue;
            }
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
        var attemptedPlayers = new ArrayList<ServerPlayer>();
        var destinationRiders = new LinkedHashMap<Entity,Entity>();
        var contacts = new HashMap<ServerPlayer,Arrival>();
        for (var player : players.keySet()) {
            if (player.isPassenger()) continue;
            for (var part : parts) {
                if (!onDeck(player,List.of(part),0.3)) continue;
                contacts.put(player,new Arrival(part.getUniqueId(),target.dimension(),
                    part.logicalPose().transformPositionInverse(player.position()),player.tickCount+100));
                break;
            }
        }
        var stagedWires = new ArrayList<ShipWires.Link>();
        try {
            prefetchDestinationChunks(target, parts, offset);
            var readyChunks = new HashSet<ChunkPos>();
            for (var part : parts) {
                var tag = SubLevelSerializer.toData(part, ids).fullTag();
                adaptSections(tag.getCompound("plot"),origin,target);
                translateRopes(tag.getCompound("plot"),offset);
                var pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));
                pose.position().add(offset.x, offset.y, offset.z);
                tag.put("pose", SableNBTUtils.writePose3d(pose));
                tag.remove("linear_velocity");
                tag.remove("angular_velocity");
                var worldChunk = worldChunk(pose.position().x, pose.position().z);
                if (readyChunks.add(worldChunk)) target.getChunk(worldChunk.x, worldChunk.z);
                var copy = SubLevelSerializer.fullyLoad(target, SubLevelSerializer.fromData(tag));
                if (copy == null) throw new IllegalStateException("Sable rejected destination plot");
                var restoredPlot = copy.getPlot();
                for (var holder : part.getPlot().getLoadedChunks()) {
                    var sourceChunk = holder.getChunk();
                    if (sourceChunk.getBlockEntities().isEmpty()) continue;
                    var restoredChunk = restoredPlot.getChunk(restoredPlot.toLocal(sourceChunk.getPos()));
                    if (restoredChunk == null) throw new IllegalStateException("Missing destination plot chunk " + sourceChunk.getPos());
                    for (var entry : sourceChunk.getBlockEntities().entrySet()) {
                        var pos = entry.getKey();
                        var restored = restoredChunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
                        if (restored == null || restored.getType() != entry.getValue().getType()
                                || restoredChunk.getBlockState(pos) != sourceChunk.getBlockState(pos))
                            throw new IllegalStateException("Incomplete block entity transfer at " + pos);
                    }
                }
                loaded.add(copy);
            }
            StationMachines.restore(loaded);
            if (WIRES) ShipWires.write(target, wireLinks, stagedWires);
            for (var entity : replacements.values()) {
                if (!target.addFreshEntity(entity)) throw new IllegalStateException("Entity transfer rejected");
                inserted.add(entity);
            }
            for (var entry : players.entrySet()) {
                var player = entry.getKey();
                var previous = entry.getValue();
                var pos = positions.get(player);
                attemptedPlayers.add(player);
                player.stopRiding();
                if (player.isPassenger()) throw new IllegalStateException("Station passenger dismount rejected");
                player.teleportTo(target, pos.x, pos.y, pos.z, previous.yaw(), previous.pitch());
                if (player.level() != target || target.getEntity(player.getUUID()) != player)
                    throw new IllegalStateException("Station passenger dimension change rejected");
                replacements.put(player, player);
            }
            for (var entry : vehicles.entrySet()) {
                if (entry.getValue() == null) continue;
                var rider = replacements.get(entry.getKey());
                var mount = replacements.get(entry.getValue());
                destinationRiders.put(rider, mount);
                if (!rider.startRiding(mount, true) || rider.getVehicle() != mount)
                    throw new IllegalStateException("Station passenger remount rejected");
            }
        } catch (RuntimeException ex) {
            for (var entry : destinationRiders.entrySet()) {
                if (entry.getKey().getVehicle() == entry.getValue())
                    FlightRollback.restoreRiding(entry.getKey(), entry.getValue(), false);
            }
            for (var player : attemptedPlayers.reversed()) players.get(player).restore(player, origin);
            for (var entry : vehicles.entrySet()) {
                if (entry.getValue() != null && entry.getKey().getVehicle() != entry.getValue())
                    FlightRollback.restoreRiding(entry.getKey(), entry.getValue(), true);
            }
            if (WIRES) ShipWires.clear(target, stagedWires);
            inserted.forEach(Entity::discard);
            for (var part : parts) {
                if (destination.getSubLevel(part.getUniqueId()) instanceof ServerSubLevel copy) {
                    StationMachines.depart(copy);
                    destination.removeSubLevel(copy, SubLevelRemovalReason.REMOVED);
                }
            }
            Sable.LOGGER.error("Station transfer could not stage its destination", ex);
            return List.of();
        }
        for (var entity : originals) {
            if (entity instanceof ServerPlayer player) {
                player.fallDistance = 0;
                player.setDeltaMovement(Vec3.ZERO);
                arrivals.remove(player);
                if (contacts.containsKey(player)) arrivals.put(player,contacts.get(player));
            } else entity.discard();
        }
        if (WIRES) ShipWires.clear(origin,wireLinks);
        for (var part : parts) {
            StationMachines.depart(part);
            source.removeSubLevel(part, SubLevelRemovalReason.REMOVED);
        }
        for(var copy:loaded) {
            Stations.track(copy);
            StationLife.remember(copy,Stations.info(copy));
        }
        OrbitSpaces.retire(origin);
        return loaded;
    }

    private static void prefetchDestinationChunks(ServerLevel target, List<ServerSubLevel> parts, Vec3 offset) {
        var chunks = target.getChunkSource();
        var requested = new HashSet<ChunkPos>();
        var center = new Vector3d();
        for (var part : parts) {
            // Sable serializes the pose around the current center of mass.
            center.set(part.getSelfMassTracker().getCenterOfMass());
            part.logicalPose().transformPosition(center);
            var chunk = worldChunk(center.x + offset.x, center.z + offset.z);
            if (requested.add(chunk) && chunks.getChunkNow(chunk.x, chunk.z) == null
                    && !Sable.HELPER.isInPlotGrid(target, chunk.getWorldPosition())) {
                // Submit all cold chunk requests before serialization and the first required getChunk.
                ((TransferChunkAccess)chunks).sableorbits$requestChunk(chunk.x, chunk.z, ChunkStatus.FULL, true);
            }
        }
    }

    private static ChunkPos worldChunk(double x, double z) {
        return new ChunkPos((int)Math.floor(x / 16), (int)Math.floor(z / 16));
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
