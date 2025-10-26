package com.creatormc.judgementdaymod.utilities;

import java.util.List;

import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;

public class LightFixer {

    /**
     * Forces a lighting update for all columns in a chunk by placing temporary
     * light blocks.
     */
    //TODO remove in future releases
    public static void forceLightUpdate(ServerLevel level, LevelChunk chunk) {
        if (Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays) <= 0)
            return;
        final int startX = chunk.getPos().x << 4;
        final int startZ = chunk.getPos().z << 4;
        final Heightmap wsHM = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        level.getServer().execute(() -> {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int topY = wsHM.getFirstAvailable(lx, lz);
                    BlockPos pulse = new BlockPos(startX + lx, topY, startZ + lz);

                    // Force light recalculation
                    level.setBlock(pulse, Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
                    level.scheduleTick(pulse, Blocks.LIGHT, 5);
                }
            }
        });
    }

       /**
     * Forces a lighting update for all columns in a chunk by placing temporary
     * light blocks in batch.
     */
    public static void forceAreaLightUpdate(List<ChunkToProcess> chunks) {
        ServerLevel level = chunks.get(0).getLevel();
        for (ChunkToProcess c : chunks) {
            LevelChunk chunk = level.getChunk(c.getChunkX(), c.getChunkZ());
            forceLightUpdate(level, chunk);
        }
    }
}
