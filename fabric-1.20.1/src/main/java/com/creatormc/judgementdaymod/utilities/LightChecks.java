package com.creatormc.judgementdaymod.utilities;

import java.util.Arrays;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Light checks of the blocks edited directly in the chunk sections.
 *
 * Vanilla queues one light engine task per block (checkBlock), each one going
 * through the chunk task sorter: with thousands of blocks (evaporated water) that
 * is most of the processing time of a chunk. Here the positions are collected and
 * handed to the light engine as a single task for all the chunks processed in a
 * batch (see begin/end), followed by a single marker task (see ChunkLightSync).
 * The task does exactly what the vanilla per block tasks do (the engines only
 * collect the positions to check, then compute them all in their next update), so
 * the computed light is the same. If another mod replaced the vanilla engines (they
 * are empty then), the positions go through the level's own checkBlock instead.
 *
 * Members of the light engine are opened by the access transformer
 * (META-INF/accesstransformer.cfg). Main server thread only.
 */
public final class LightChecks {

    // Positions checked in the block and sky light, and in the sky light only
    private static long[] both = new long[1024];
    private static int bothCount;
    private static long[] skyOnly = new long[1024];
    private static int skyOnlyCount;
    // Level and chunk of the collected checks, and the chunks waiting for a marker
    private static ServerLevel batchLevel;
    private static long batchChunk;
    private static final LongArrayList markerChunks = new LongArrayList();
    private static int depth;

    // Set if the light engine could not be used directly: per block vanilla checks
    private static boolean unavailable = false;

    private final ServerLevel level;
    private final int chunkX;
    private final int chunkZ;
    // Block light left alone (only the sky light is checked)
    private final boolean sky;
    private int count;

    public LightChecks(ServerLevel level, int chunkX, int chunkZ, boolean skyLightOnly) {
        this.level = level;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sky = skyLightOnly;
    }

    /**
     * Collected checks are queued together when the outermost batch ends.
     */
    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0 && --depth == 0) {
            flush();
        }
    }

    public void add(BlockPos pos) {
        if (batchLevel != level) {
            // Checks of another level: queue the ones collected so far first
            flush();
            batchLevel = level;
            batchChunk = ChunkPos.asLong(chunkX, chunkZ);
        }
        if (sky) {
            if (skyOnlyCount == skyOnly.length)
                skyOnly = Arrays.copyOf(skyOnly, skyOnlyCount * 2);
            skyOnly[skyOnlyCount++] = pos.asLong();
        } else {
            if (bothCount == both.length)
                both = Arrays.copyOf(both, bothCount * 2);
            both[bothCount++] = pos.asLong();
        }
        count++;
    }

    /**
     * The checks of this chunk are complete.
     *
     * @return true if light checks were collected for it
     */
    public boolean submit() {
        if (count == 0) {
            return false;
        }
        count = 0;
        if (depth == 0) {
            flush();
        }
        return true;
    }

    /**
     * Runs the task, through the server thread, once the light engine has computed
     * every check queued before it. Queued with the current batch.
     */
    static void afterLight(ServerLevel level, int chunkX, int chunkZ) {
        if (batchLevel != level) {
            flush();
            batchLevel = level;
            batchChunk = ChunkPos.asLong(chunkX, chunkZ);
        }
        markerChunks.add(ChunkPos.asLong(chunkX, chunkZ));
        if (depth == 0) {
            flush();
        }
    }

    /**
     * Queues the collected checks, then the marker of the chunks waiting for them.
     */
    private static void flush() {
        final ServerLevel level = batchLevel;
        if (level == null || (bothCount == 0 && skyOnlyCount == 0 && markerChunks.isEmpty())) {
            return;
        }
        final ThreadedLevelLightEngine engine = level.getChunkSource().getLightEngine();
        final int x = ChunkPos.getX(batchChunk);
        final int z = ChunkPos.getZ(batchChunk);
        final long[] bothList = Arrays.copyOf(both, bothCount);
        final long[] skyList = Arrays.copyOf(skyOnly, skyOnlyCount);
        final long[] markers = markerChunks.toLongArray();
        bothCount = 0;
        skyOnlyCount = 0;
        // Do not keep the room of an exceptionally big batch (an ocean evaporating)
        if (both.length > 65536)
            both = new long[1024];
        if (skyOnly.length > 65536)
            skyOnly = new long[1024];
        markerChunks.clear();
        batchLevel = null;

        if (!unavailable) {
            try {
                if (engine.blockEngine == null && engine.skyEngine == null) {
                    // Another mod replaced the light engines (e.g. Starlight leaves both empty):
                    // the checks go through the level's own entry point, which it handles, still
                    // ahead of the markers. Its engine checks both layers of every position
                    for (long p : bothList) {
                        engine.checkBlock(BlockPos.of(p));
                    }
                    for (long p : skyList) {
                        engine.checkBlock(BlockPos.of(p));
                    }
                } else if (bothList.length > 0 || skyList.length > 0) {
                    engine.addTask(x, z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                        // Same as LevelLightEngine.checkBlock for every position
                        final LightEngine<?, ?> blockEngine = engine.blockEngine;
                        final LightEngine<?, ?> skyEngine = engine.skyEngine;
                        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                        for (long p : bothList) {
                            pos.set(p);
                            if (blockEngine != null)
                                blockEngine.checkBlock(pos);
                            if (skyEngine != null)
                                skyEngine.checkBlock(pos);
                        }
                        if (skyEngine != null) {
                            for (long p : skyList) {
                                skyEngine.checkBlock(pos.set(p));
                            }
                        }
                    });
                }
                if (markers.length > 0) {
                    // Back to the server thread. tell() always queues the task: once the
                    // server is stopped execute() would run it right here, on the light thread
                    final MinecraftServer server = level.getServer();
                    engine.addTask(x, z, ThreadedLevelLightEngine.TaskType.POST_UPDATE,
                            () -> server.tell(new TickTask(server.getTickCount(),
                                    () -> ChunkLightSync.markersDone(level, markers))));
                }
                return;
            } catch (LinkageError e) {
                unavailable = true;
                JudgementDayMod.LOGGER.warn("[Apocalypse] Light engine not accessible, using per block light checks: " + e);
            }
        }

        // Fallback: the vanilla way, one task per block. It checks the block light too,
        // so the sky light only checks are not done (as in the versions before them)
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long p : bothList) {
            engine.checkBlock(pos.set(p));
        }
        ChunkLightSync.markersLost(level, markers);
    }

    /**
     * Drops what is collected (server stopping).
     */
    public static void clear() {
        bothCount = 0;
        skyOnlyCount = 0;
        markerChunks.clear();
        batchLevel = null;
        depth = 0;
    }
}
