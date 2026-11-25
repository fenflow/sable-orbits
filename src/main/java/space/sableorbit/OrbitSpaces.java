package space.sableorbit;

import com.ntmspace.orbit.Orbit;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.io.IOException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import net.neoforged.neoforge.event.level.LevelEvent;
import java.util.*;

public final class OrbitSpaces extends SavedData {
    private static final int RETIREMENT_DELAY = 100;
    private static final int UNLOADS_PER_RETIRING_ORBIT = 16;
    private static final int UNLOADS_PER_SERVER_TICK = 64;
    private final Set<String> names = new HashSet<>();
    private final Set<String> parked = new HashSet<>();
    private final SequencedMap<ServerLevel, Integer> retirements = new LinkedHashMap<>();
    private int unloadBudgetTick = -1;
    private int unloadsRemaining;
    final CompoundTag addresses = new CompoundTag();
    private byte[] pinKey = new byte[0];
    byte[] pinKey() {
        if (pinKey.length == 0) {
            pinKey = new byte[32];
            new java.security.SecureRandom().nextBytes(pinKey);
            setDirty();
        }
        return pinKey;
    }
    private static OrbitSpaces load(CompoundTag tag, HolderLookup.Provider provider) {
        var data = new OrbitSpaces();
        for (var key:tag.getAllKeys()) if (key.startsWith("station_")) data.names.add(key);
        for (var name : tag.getCompound("parked").getAllKeys()) if (data.names.contains(name)) data.parked.add(name);
        data.pinKey = tag.getByteArray("pinKey");
        data.addresses.merge(tag.getCompound("addresses"));
        return data;
    }
    static OrbitSpaces data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(OrbitSpaces::new, OrbitSpaces::load), "sableorbits_spaces");
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putByteArray("pinKey", pinKey.clone());
        tag.put("addresses",addresses.copy());
        names.forEach(name -> tag.putBoolean(name, true));
        var savedParked = new CompoundTag();
        parked.forEach(name -> savedParked.putBoolean(name, true));
        tag.put("parked", savedParked);
        return tag;
    }
    public static boolean isolated(Level level) {
        return level.dimension().location().getNamespace().equals("sableorbits");
    }
    public static boolean unoccupied(Level level) {
        return level instanceof ServerLevel server && isolated(server) && server.players().isEmpty();
    }
    static void restore(MinecraftServer server) {
        var store = data(server);
        Hooks.watchNativeMigrations(server.getLevel(Orbit.ORBIT));
        for (String name : List.copyOf(store.names)) {
            if (!store.parked.contains(name)) retire(open(server, name));
        }
    }

    static void retire(ServerLevel level) {
        if (!isolated(level) || !level.dimension().location().getPath().startsWith("station_")) return;
        var server = level.getServer();
        data(server).retirements.putFirst(level, server.getTickCount() + RETIREMENT_DELAY);
    }

    public static int parkingUnloadAllowance(ServerLevel level) {
        if (!isolated(level) || level.noSave() || !level.players().isEmpty()
                || level.getChunkSource().getLoadedChunksCount() != 0) return 0;
        var server = level.getServer();
        var store = data(server);
        var due = store.retirements.get(level);
        int tick = server.getTickCount();
        if (due == null || tick - due < 0 || !noPlots(level) || ForcedChunkManager.hasForcedChunks(level)) return 0;
        if (store.unloadBudgetTick != tick) {
            store.unloadBudgetTick = tick;
            store.unloadsRemaining = UNLOADS_PER_SERVER_TICK;
        }
        int allowance = Math.min(UNLOADS_PER_RETIRING_ORBIT, store.unloadsRemaining);
        store.unloadsRemaining -= allowance;
        return allowance;
    }

    static void drainRetirements(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        var store = data(server);
        for (var pending : List.copyOf(store.retirements.entrySet())) {
            var level = pending.getKey();
            if (server.getTickCount() - pending.getValue() < 0) continue;
            if (server.forgeGetWorldMap().get(level.dimension()) != level || !noPlots(level)) {
                store.retirements.remove(level);
                continue;
            }
            if (level.noSave() || !level.players().isEmpty() || level.getChunkSource().chunkMap.hasWork()
                    || level.getAllEntities().iterator().hasNext() || ForcedChunkManager.hasForcedChunks(level)) continue;

            // Wait until chunk and entity unloads settle, then persist the level and Sable's empty occupancy.
            level.save(null, false, false);
            if (empty(level) && !ForcedChunkManager.hasForcedChunks(level) && !level.getChunkSource().chunkMap.hasWork() && unload(level)) {
                store.retirements.remove(level);
                var name = level.dimension().location().getPath();
                if (store.names.contains(name) && store.parked.add(name)) store.setDirty();
            }
            // Avoid a burst of disk flushes when an older save contains several empty orbits.
            break;
        }
    }

    public static ServerLevel reopen(MinecraftServer server, ResourceKey<Level> dimension) {
        if (!dimension.location().getNamespace().equals("sableorbits") || !server.isSameThread()
                || !server.isRunning() || server.overworld() == null) return null;
        var name = dimension.location().getPath();
        if (!data(server).parked.contains(name)) return null;
        var level = open(server, name);
        retire(level);
        return level;
    }

    static ResourceKey<Level> key(String name) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("sableorbits", name));
    }

    static String homeName(ServerLevel origin, UUID station) {
        String name = "station_" + station;
        return origin.dimension().equals(key(name)) ? name + "_solo" : name;
    }

    static List<ServerSubLevel> transfer(List<ServerSubLevel> parts, String name, Vec3 offset) {
        return transfer(parts, name, offset, List.of());
    }

    static List<ServerSubLevel> transfer(List<ServerSubLevel> parts, String name, Vec3 offset, Collection<ServerPlayer> standingBeforeAssembly) {
        var server = parts.getFirst().getLevel().getServer();
        var store = data(server);
        var existing = server.forgeGetWorldMap().get(key(name));
        boolean provisional = existing == null && !store.names.contains(name);
        boolean wasParked = store.parked.contains(name);
        var target = existing == null ? open(server, name, !provisional) : existing;
        List<ServerSubLevel> moved = List.of();
        try {
            moved = Flight.transfer(parts, target, offset, standingBeforeAssembly);
            return moved;
        } finally {
            if (provisional && moved.isEmpty() && empty(target)) {
                unload(target);
            } else if (!moved.isEmpty() || provisional) {
                store.remember(name);
            } else if (wasParked) {
                retire(target);
            }
        }
    }

    private static boolean noPlots(ServerLevel level) {
        var plots = SubLevelContainer.getContainer(level);
        return plots != null && plots.getAllSubLevels().isEmpty() && plots.getAllTickets().isEmpty()
            && plots.getOccupancy().isEmpty();
    }

    private static boolean empty(ServerLevel level) {
        return level.players().isEmpty() && noPlots(level) && !level.getAllEntities().iterator().hasNext();
    }

    private static boolean unload(ServerLevel level) {
        var server = level.getServer();
        if (!server.forgeGetWorldMap().remove(level.dimension(), level)) return false;
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        try {
            level.close();
        } catch (IOException error) {
            Sable.LOGGER.error("Could not close unused orbit {}", level.dimension().location(), error);
        }
        return true;
    }

    static ServerLevel open(MinecraftServer server, String name) {
        return open(server, name, true);
    }

    private static ServerLevel open(MinecraftServer server, String name, boolean remember) {
        var key = key(name);
        var current = server.forgeGetWorldMap().get(key);
        if (current != null) {
            if (remember) data(server).remember(name);
            return current;
        }
        var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(Orbit.ORBIT.location());
        if (stem == null) {
            var type = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Orbit.ORBIT.location()));
            var biome = server.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(ResourceKey.create(Registries.BIOME, Orbit.ORBIT.location()));
            stem = new net.minecraft.world.level.dimension.LevelStem(type, new com.ntmspace.orbit.OrbitChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome)));
        }
        var worldData = server.getWorldData();
        var level = new ServerLevel(server, net.minecraft.Util.backgroundExecutor(), ((space.sableorbit.mixin.ServerAccess)server).sableorbits$storage(),
            new DerivedLevelData(worldData, worldData.overworldData()), key, stem,
            LoggerChunkProgressListener.createFromGameruleRadius(0), server.overworld().isDebug(),
            BiomeManager.obfuscateSeed(server.overworld().getSeed()), List.of(), false, server.overworld().getRandomSequences());
        server.forgeGetWorldMap().put(key, level);
        server.markWorldsDirty();
        if (remember) data(server).remember(name);
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }

    private void remember(String name) {
        boolean added = names.add(name);
        if (parked.remove(name) || added) setDirty();
    }
}
