package com.creatormc.judgementdaymod.utilities;

import javax.annotation.Nonnull;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public class ApocalypseChunkData extends SavedData {
    // Primitive map (no Long/Integer boxing). Accessed both by the server thread and by
    // the world generation threads (buildSurface), so every access is synchronized.
    private final Long2IntOpenHashMap chunkPhases = new Long2IntOpenHashMap();
    // Chunks without ores that got some from a neighbour's (see OreRetrofit): theirs are
    // still to place
    private final LongOpenHashSet oresDue = new LongOpenHashSet();
    // An older version may have generated chunks without ores in this world (see
    // OreRetrofit): decided the first time this version loads it, then kept
    private boolean oresRetrofit;

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

    public synchronized boolean oresRetrofit() {
        return oresRetrofit;
    }

    public synchronized boolean isOresDue(long chunk) {
        return oresDue.contains(chunk);
    }

    public synchronized void setOresDue(long chunk, boolean due) {
        if (due ? oresDue.add(chunk) : oresDue.remove(chunk))
            setDirty();
    }

    public static ApocalypseChunkData load(CompoundTag tag) {
        ApocalypseChunkData data = new ApocalypseChunkData();
        for (long chunk : tag.getLongArray("OresDue"))
            data.oresDue.add(chunk);
        CompoundTag chunksTag = tag.getCompound("Chunks");

        for (String key : chunksTag.getAllKeys()) {
            long chunkKey = Long.parseLong(key);
            int phase = chunksTag.getInt(key);
            data.chunkPhases.put(chunkKey, phase);
        }
        if (tag.contains("OresRetrofit")) {
            data.oresRetrofit = tag.getBoolean("OresRetrofit");
        } else {
            // Written by an older version: it generated chunks without ores if the
            // apocalypse reached its peak (the config of the world is loaded first)
            data.oresRetrofit = !chunksTag.isEmpty() && (ConfigManager.apocalypseActive
                    || ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseMaxDays
                    || ConfigManager.lastApocalypseStep != ConfigManager.UNSET);
            data.setDirty();
        }
        return data;
    }

    @Override
    public CompoundTag save(@Nonnull CompoundTag tag) {
        // Copy under the lock, build the (much slower) tag outside it: world generation
        // threads setting phases meanwhile do not have to wait
        final long[] keys;
        final int[] phases;
        final long[] due;
        final boolean retrofit;
        synchronized (this) {
            due = oresDue.toLongArray();
            retrofit = oresRetrofit;
            keys = new long[chunkPhases.size()];
            phases = new int[keys.length];
            int i = 0;
            for (Long2IntMap.Entry entry : chunkPhases.long2IntEntrySet()) {
                keys[i] = entry.getLongKey();
                phases[i] = entry.getIntValue();
                i++;
            }
        }
        CompoundTag chunksTag = new CompoundTag();
        for (int i = 0; i < keys.length; i++) {
            chunksTag.putInt(String.valueOf(keys[i]), phases[i]);
        }
        tag.put("Chunks", chunksTag);
        tag.putBoolean("OresRetrofit", retrofit);
        if (due.length > 0)
            tag.putLongArray("OresDue", due);
        return tag;
    }
}
