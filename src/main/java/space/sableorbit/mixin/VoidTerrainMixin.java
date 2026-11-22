package space.sableorbit.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.sableorbit.OrbitSpaces;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
abstract class VoidTerrainMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos pos) { throw new AssertionError(); }
    // Reuse the normal chunk-load read. Evicted or unfinished reads fall back to saving.
    @Unique private final Map<Long,CompletableFuture<Boolean>> sableorbits$missing=Collections.synchronizedMap(new LinkedHashMap<>());
    @Redirect(method="scheduleChunkLoad",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> rememberRead(ChunkMap map,ChunkPos pos) {
        var read=readChunk(pos);
        if(OrbitSpaces.isolated(level)) synchronized(sableorbits$missing) {
            if(sableorbits$missing.size()>=8192) sableorbits$missing.remove(sableorbits$missing.keySet().iterator().next());
            sableorbits$missing.put(pos.toLong(),read.handle((tag,error)->error==null && tag.isEmpty()));
        }
        return read;
    }
    @Unique private static final Set<String> sableorbits$vanillaKeys=Set.of("Status","xPos","yPos","zPos","LastUpdate",
        "DataVersion","InhabitedTime","structures","Heightmaps","sections","entities","block_entities","block_ticks","fluid_ticks",
        "PostProcessing","CarvingMasks","isLightOn");

    @Inject(method="save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",at=@At(value="INVOKE",
        target="Lnet/minecraft/server/level/ChunkMap;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;"),cancellable=true)
    private void skipPristineVoid(ChunkAccess chunk, CallbackInfoReturnable<Boolean> result,@Local CompoundTag data) {
        if(!OrbitSpaces.isolated(level))return;
        var missing=sableorbits$missing.remove(chunk.getPos().toLong());
        if(missing==null || !missing.getNow(false))return;
        if (dev.ryanhcode.sable.Sable.HELPER.isInPlotGrid(level,chunk.getPos().getWorldPosition())
                || !sableorbits$vanillaKeys.containsAll(data.getAllKeys()) || data.getLong("InhabitedTime")!=0) return;
        for(String key:List.of("entities","block_entities","block_ticks","fluid_ticks"))
            if(!data.getList(key,Tag.TAG_COMPOUND).isEmpty()) return;
        if(!data.getCompound("CarvingMasks").isEmpty()) return;
        var structures=data.getCompound("structures");
        for(String key:structures.getAllKeys()) if(!structures.getCompound(key).isEmpty()) return;
        for(var list:data.getList("PostProcessing",Tag.TAG_LIST)) if(!((ListTag)list).isEmpty()) return;
        for(var raw:data.getList("sections",Tag.TAG_COMPOUND)) {
            var section=(CompoundTag)raw;
            if(!Set.of("block_states","biomes","Y","SkyLight","BlockLight").containsAll(section.getAllKeys())) return;
            if(section.contains("block_states")) {
                var states=section.getCompound("block_states").getList("palette",Tag.TAG_COMPOUND);
                if(states.size()!=1 || !states.getCompound(0).getString("Name").equals("minecraft:air")) return;
            }
            if(section.contains("biomes")) {
                var biomes=section.getCompound("biomes").getList("palette",Tag.TAG_STRING);
                if(biomes.size()!=1) return;
                String biome=biomes.getString(0);
                if(!biome.equals("ntmspace:orbit") && !(data.getString("Status").equals("minecraft:structure_starts") && biome.equals("minecraft:plains"))) return;
            }
        }
        // An existing region entry might represent a player edit later cleared back to air.
        sableorbits$missing.put(chunk.getPos().toLong(),missing);
        result.setReturnValue(false);
    }
}

