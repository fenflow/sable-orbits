package space.sableorbit;

import com.ntmspace.orbit.Orbit;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import java.util.*;

public final class OrbitSpaces extends SavedData {
    private final Set<String> names = new HashSet<>();
    final CompoundTag addresses = new CompoundTag();
    private static OrbitSpaces load(CompoundTag tag, HolderLookup.Provider provider) {
        var data = new OrbitSpaces();
        for (var key:tag.getAllKeys()) if (key.startsWith("station_")) data.names.add(key);
        data.addresses.merge(tag.getCompound("addresses"));
        return data;
    }
    static OrbitSpaces data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(OrbitSpaces::new, OrbitSpaces::load), "sableorbits_spaces");
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put("addresses",addresses.copy());
        names.forEach(name -> tag.putBoolean(name, true));
        return tag;
    }
    public static boolean isolated(Level level) {
        return level.dimension().location().getNamespace().equals("sableorbits");
    }
    public static boolean unoccupied(Level level) {
        return level instanceof ServerLevel server && isolated(server) && server.players().isEmpty();
    }
    static void restore(MinecraftServer server) {
        for (String name : List.copyOf(data(server).names)) open(server, name);
    }
    static void retire(ServerLevel level) {
        var store=data(level.getServer());
        if(store.names.remove(level.dimension().location().getPath())) store.setDirty();
    }
    static ServerLevel open(MinecraftServer server, String name) {
        var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("sableorbits", name));
        var current = server.getLevel(key);
        if (current != null) {
            var store=data(server); if(store.names.add(name)) store.setDirty();
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
        var data = data(server);
        if (data.names.add(name)) data.setDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }
}
