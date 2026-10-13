package com.creatormc.judgementdaymod.utilities;

import java.util.Arrays;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sends processed chunks to the clients with their final light.
 *
 * The light checks queued for the edited blocks (see LightChecks) are computed later
 * by the light thread, and vanilla only forwards later light changes to players at
 * the edge of their view distance: a chunk sent again right after its edits would
 * keep showing the old light. So after the checks of each chunk a marker task is
 * queued in the light engine, which runs it once they are computed. A processed
 * chunk is sent again once the markers of the 3x3 area around it are done (light
 * spreads at most 15 blocks), at the start of the next tick: it carries its final
 * light. Light changes made later by normal block updates are relit by the clients
 * themselves, as in vanilla.
 * If markers are not run (another mod changed the light engine) chunks are sent
 * again right away, as before.
 *
 * Main server thread only (the marker hands back to it).
 */
public final class ChunkLightSync {

    // Longest wait for a marker: past it the chunk is sent anyway and, if no marker at
    // all came back meanwhile, markers are not used any more
    private static final int MARKER_TIMEOUT_TICKS = 600;
    // Chunks sent again per tick at most (as many as are processed per tick): markers of
    // several ticks can come back together, the rest waits for the next tick
    private static final int RESENDS_PER_TICK = 16;

    private static final class Pending {
        final ServerLevel level;
        // Markers still running in the light engine for chunks of this area
        int markers;
        // The chunk was processed: send it again once the markers of its area are done
        boolean resend;
        long timeoutTick;

        Pending(ServerLevel level) {
            this.level = level;
        }
    }

    private static final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();
    // Chunks lit and waiting to be sent again at the start of the next tick
    private static final LongArrayList lit = new LongArrayList();
    private static long tick = 0;
    // False once markers stopped coming back: chunks are sent again right away
    private static boolean markersWork = true;
    // Tick of the last marker that came back
    private static long lastMarkerTick = 0;
    // False if the chunk holders cannot be accessed (access transformer not applied)
    private static boolean holderAccess = true;

    private ChunkLightSync() {
    }

    /**
     * Light checks were just queued for the given chunk: the chunks of the 3x3 area
     * around it wait for them before being sent again.
     */
    public static void lightQueued(ServerLevel level, int chunkX, int chunkZ) {
        if (!markersWork)
            return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                Pending p = pending.get(key);
                if (p == null) {
                    p = new Pending(level);
                    pending.put(key, p);
                }
                p.markers++;
                p.timeoutTick = tick + MARKER_TIMEOUT_TICKS;
            }
        }
        LightChecks.afterLight(level, chunkX, chunkZ);
    }

    /**
     * The chunk was edited and must be sent again to the players viewing it. If light
     * checks are being computed around it, it is sent once they are done.
     *
     * @return false if the caller must send it right away
     */
    public static boolean resendWhenLit(LevelChunk chunk) {
        Pending p = pending.get(chunk.getPos().toLong());
        if (p == null || p.markers == 0 || p.level != chunk.getLevel()) {
            return false;
        }
        p.resend = true;
        return true;
    }

    /**
     * The light checks queued before the markers of these chunks are computed.
     */
    static void markersDone(ServerLevel level, long[] chunks) {
        lastMarkerTick = tick;
        for (long chunk : chunks) {
            markerDone(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        }
    }

    /**
     * The markers of these chunks could not be queued: chunks are sent right away from now on.
     */
    static void markersLost(ServerLevel level, long[] chunks) {
        if (markersWork) {
            markersWork = false;
            JudgementDayMod.LOGGER.warn("[Apocalypse] Light markers unavailable, chunks are sent again right away");
        }
        for (long chunk : chunks) {
            markerDone(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        }
    }

    private static void markerDone(ServerLevel level, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                Pending p = pending.get(key);
                if (p == null || p.level != level)
                    continue;
                if (p.markers > 0)
                    p.markers--;
                if (p.markers == 0) {
                    if (p.resend)
                        lit.add(key);
                    else
                        pending.remove(key);
                }
            }
        }
    }

    /**
     * Sends again the processed chunks whose light is computed. Called at the start of
     * every server tick: the light change notifications queued with the markers are in
     * by then, so vanilla does not send them a second time (see dropQueuedChanges).
     */
    public static void beforeTick() {
        if (lit.isEmpty())
            return;
        int sent = 0;
        int i = 0;
        while (i < lit.size() && sent < RESENDS_PER_TICK) {
            final long key = lit.getLong(i++);
            final Pending p = pending.get(key);
            if (p != null && p.resend && p.markers == 0) {
                resend(p, key);
                pending.remove(key);
                sent++;
            }
        }
        lit.removeElements(0, i);
    }

    /**
     * Sends the chunks whose markers did not come back in time. Called once per server tick.
     */
    public static void tick() {
        tick++;
        if (pending.isEmpty())
            return;

        ObjectIterator<Long2ObjectMap.Entry<Pending>> it = pending.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Pending> entry = it.next();
            // Read the entry before any removal: the fast iterator reuses it
            final long key = entry.getLongKey();
            final Pending p = entry.getValue();
            if (p.markers == 0 || tick < p.timeoutTick)
                continue;

            // If no marker at all came back meanwhile the light engine does not run them
            if (markersWork && tick - lastMarkerTick >= MARKER_TIMEOUT_TICKS) {
                markersWork = false;
                JudgementDayMod.LOGGER.warn("[Apocalypse] Light markers are not run, chunks are sent again right away");
            }
            if (p.resend)
                resend(p, key);
            it.remove();
        }
    }

    /**
     * Sends the whole chunk again (with its current light) to the players viewing it.
     */
    private static void resend(Pending p, long key) {
        p.resend = false;
        ChunkPos pos = new ChunkPos(key);
        LevelChunk chunk = p.level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk != null) {
            dropQueuedChanges(p.level, key);
            Generator.updateClients(chunk, p.level);
        }
    }

    /**
     * The chunk is about to be sent whole, with its current blocks and light: the block
     * and light changes vanilla has queued for it so far are in it, they are not sent
     * a second time.
     */
    private static void dropQueuedChanges(ServerLevel level, long key) {
        if (!holderAccess)
            return;
        try {
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(key);
            if (holder != null) {
                holder.skyChangedLightSectionFilter.clear();
                holder.blockChangedLightSectionFilter.clear();
                if (holder.hasChangedSections) {
                    Arrays.fill(holder.changedBlocksPerSection, null);
                    holder.hasChangedSections = false;
                }
            }
        } catch (LinkageError e) {
            holderAccess = false;
            JudgementDayMod.LOGGER.warn("[Apocalypse] Chunk holder not accessible: " + e);
        }
    }

    public static void clear() {
        lit.clear();
        pending.clear();
        tick = 0;
        markersWork = true;
        lastMarkerTick = 0;
    }
}
