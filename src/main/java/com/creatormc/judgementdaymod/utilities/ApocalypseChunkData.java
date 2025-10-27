package com.creatormc.judgementdaymod.utilities;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public class ApocalypseChunkData extends SavedData {
    private final Map<Long, Integer> chunkPhases = new HashMap<>();

    private static final String FILE_ID = "judgementday_chunkdata";

    public static ApocalypseChunkData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            ApocalypseChunkData::load,
            ApocalypseChunkData::new,
            FILE_ID
        );
    }

    public int getPhase(ChunkPos pos) {
        return chunkPhases.getOrDefault(pos.toLong(), -1);
    }

    public void setPhase(ChunkPos pos, int phase) {
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
    public CompoundTag save(@Nonnull CompoundTag tag) {
        CompoundTag chunksTag = new CompoundTag();
        for (Map.Entry<Long, Integer> entry : chunkPhases.entrySet()) {
            chunksTag.putInt(String.valueOf(entry.getKey()), entry.getValue());
        }
        tag.put("Chunks", chunksTag);
        return tag;
    }
}
