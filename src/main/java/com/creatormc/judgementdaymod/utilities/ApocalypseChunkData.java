package com.creatormc.judgementdaymod.utilities;

import javax.annotation.Nonnull;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public class ApocalypseChunkData extends SavedData {
    // Primitive map (no Long/Integer boxing). Accessed both by the server thread and by
    // the world generation threads (buildSurface), so every access is synchronized.
    private final Long2IntOpenHashMap chunkPhases = new Long2IntOpenHashMap();

    private static final String FILE_ID = "judgementday_chunkdata";

    public ApocalypseChunkData() {
        chunkPhases.defaultReturnValue(-1);
    }

    public static ApocalypseChunkData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            ApocalypseChunkData::load,
            ApocalypseChunkData::new,
            FILE_ID
        );
    }

    public synchronized int getPhase(ChunkPos pos) {
        return chunkPhases.get(pos.toLong());
    }

    public synchronized void setPhase(ChunkPos pos, int phase) {
        chunkPhases.put(pos.toLong(), phase);
        setDirty(); // segnala che deve essere salvato su disco
    }

    public static ApocalypseChunkData load(CompoundTag tag) {
        ApocalypseChunkData data = new ApocalypseChunkData();
        CompoundTag chunksTag = tag.getCompound("Chunks");

        for (String key : chunksTag.getAllKeys()) {
            long chunkKey = Long.parseLong(key);
            int phase = chunksTag.getInt(key);
            data.chunkPhases.put(chunkKey, phase);
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(@Nonnull CompoundTag tag) {
        CompoundTag chunksTag = new CompoundTag();
        for (Long2IntMap.Entry entry : chunkPhases.long2IntEntrySet()) {
            chunksTag.putInt(String.valueOf(entry.getLongKey()), entry.getIntValue());
        }
        tag.put("Chunks", chunksTag);
        return tag;
    }
}
