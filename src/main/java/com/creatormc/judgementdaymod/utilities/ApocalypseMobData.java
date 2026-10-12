package com.creatormc.judgementdaymod.utilities;

import javax.annotation.Nonnull;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Day on which the mobs of each chunk were last saved to disk (see
 * UnattendedChunkHandler). Kept apart from ApocalypseChunkData so that writing
 * these days never rewrites the (much bigger) chunk phase table. Main thread only.
 */
public class ApocalypseMobData extends SavedData {
    private final Long2IntOpenHashMap savedDays = new Long2IntOpenHashMap();

    private static final String FILE_ID = "judgementday_mobdays";

    public ApocalypseMobData() {
        savedDays.defaultReturnValue(ConfigManager.UNSET);
    }

    public static ApocalypseMobData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ApocalypseMobData::load, ApocalypseMobData::new, FILE_ID);
    }

    /**
     * Day the mobs of the chunk were last saved, ConfigManager.UNSET if unknown.
     */
    public int getSavedDay(long chunkKey) {
        return savedDays.get(chunkKey);
    }

    public void setSavedDay(long chunkKey, int day) {
        if (savedDays.put(chunkKey, day) != day) {
            setDirty();
        }
    }

    public static ApocalypseMobData load(CompoundTag tag) {
        ApocalypseMobData data = new ApocalypseMobData();
        long[] keys = tag.getLongArray("Chunks");
        int[] days = tag.getIntArray("Days");
        for (int i = 0; i < Math.min(keys.length, days.length); i++) {
            data.savedDays.put(keys[i], days[i]);
        }
        return data;
    }

    @Override
    public CompoundTag save(@Nonnull CompoundTag tag) {
        long[] keys = new long[savedDays.size()];
        int[] days = new int[savedDays.size()];
        int i = 0;
        for (Long2IntMap.Entry entry : savedDays.long2IntEntrySet()) {
            keys[i] = entry.getLongKey();
            days[i] = entry.getIntValue();
            i++;
        }
        tag.putLongArray("Chunks", keys);
        tag.putIntArray("Days", days);
        return tag;
    }
}
