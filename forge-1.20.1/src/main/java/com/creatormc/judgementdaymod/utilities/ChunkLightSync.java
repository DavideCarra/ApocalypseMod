package com.creatormc.judgementdaymod.utilities;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import com.creatormc.judgementdaymod.handlers.DayTracker;
import com.creatormc.judgementdaymod.handlers.UnattendedChunkHandler;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.LightLayer;
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
 * The light a processed chunk spills over into its neighbours is not in that resend,
 * and vanilla only tells the players at the edge of their view distance about it
 * (the others relight their chunks from the block changes they get, which the resend
 * replaces): the light sections that change in the neighbours of a processed chunk,
 * until its light is computed, are sent to the players viewing them at the start of the
 * tick after that, unless the neighbour is about to be sent again whole anyway (or still waits
 * to be processed, which sends it again whole if it changes).
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
        // Fires lit by the processing, which do not glow (see firesLit)
        LongArrayList fires;
        // Light sections around the block changes vanilla sent while the chunk waited to be
        // sent again whole (see beforeBroadcast)
        BitSet relit;

        Pending(ServerLevel level) {
            this.level = level;
        }
    }

    private static final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();

    // Ticks the light changes of a neighbour are still collected after the light of the
    // processed chunks around it is computed (the notifications come back separately)
    private static final int SPILL_GRACE_TICKS = 2;

    private static final class Spill {
        final ServerLevel level;
        // Light sections changed since they were last sent (index from the lowest light section)
        final BitSet sky = new BitSet();
        final BitSet block = new BitSet();
        // Processed chunks around it whose light is not computed yet (markers out, by chunk)
        final Long2IntOpenHashMap waitingFor = new Long2IntOpenHashMap();
        // Last tick its light changes are collected
        long until;

        Spill(ServerLevel level) {
            this.level = level;
        }
    }

    // Neighbours of processed chunks whose light changes are collected
    private static final Long2ObjectOpenHashMap<Spill> spills = new Long2ObjectOpenHashMap<>();

    private static final class FollowUp {
        final ServerLevel level;
        // Light sections changed since the chunk (or its neighbour) was sent again whole
        final BitSet sky = new BitSet();
        final BitSet block = new BitSet();
        // Markers queued when they were sent, still out
        int markers;
        long timeoutTick;

        FollowUp(ServerLevel level) {
            this.level = level;
        }
    }

    // Chunks sent again whole and their neighbours, whose light changes are collected until
    // the light engine has computed the checks queued before they were sent (see followUp)
    private static final Long2ObjectOpenHashMap<FollowUp> followUps = new Long2ObjectOpenHashMap<>();
    // Chunks lit and waiting to be sent again at the start of the next tick
    private static final LongArrayList lit = new LongArrayList();
    private static long tick = 0;
    // False once markers stopped coming back: chunks are sent again right away
    private static boolean markersWork = true;
    // Tick of the last marker that came back
    private static long lastMarkerTick = 0;
    // False if the chunk holders cannot be accessed (access transformer not applied)
    private static boolean holderAccess = true;
    // False if the chunk source's executor cannot be accessed (access transformer not applied)
    private static boolean chunkExecutorAccess = true;

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
                if (dx != 0 || dz != 0) {
                    Spill s = spills.get(key);
                    if (s == null || s.level != level) {
                        s = new Spill(level);
                        spills.put(key, s);
                    }
                    s.waitingFor.addTo(ChunkPos.asLong(chunkX, chunkZ), 1);
                    s.until = Math.max(s.until, tick + MARKER_TIMEOUT_TICKS + SPILL_GRACE_TICKS);
                }
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
     * Fires that do not glow were lit in the chunk by its processing (see
     * Generator.transformSurface): their light is not computed, the chunk sent again whole
     * carries them dark.
     */
    public static void firesLit(ServerLevel level, long chunk, LongArrayList positions) {
        final Pending p = pending.get(chunk);
        if (p == null || p.level != level || p.markers == 0)
            return;
        if (p.fires == null)
            p.fires = positions;
        else
            p.fires.addAll(positions);
    }

    /**
     * Vanilla is about to send the block changes of the chunk (ChunkHolder.broadcastChanges)
     * while it waits to be sent again whole. A client applies them to its copy of the chunk
     * from before the processing and relights the blocks itself, sometimes only after the
     * chunk sent again replaced that copy (it applies the light it is sent once per frame,
     * after the blocks), next to neighbours not sent again yet: the light sections around the
     * changes are sent again once the light settles (see sentWhole). The changes at the fires
     * that do not glow are left out (the chunk sent again carries them): the client would
     * compute the light of the fire and draw a glow the server does not have.
     */
    public static void beforeBroadcast(ServerLevel level, long chunk, ShortSet[] changed, LevelHeightAccessor heights) {
        if (pending.isEmpty())
            return;
        final Pending p = pending.get(chunk);
        if (p == null || !p.resend || p.level != level)
            return;
        // Light sections: one more below and above the block sections
        for (int index = 0; index < changed.length; index++) {
            if (changed[index] != null) {
                if (p.relit == null)
                    p.relit = new BitSet();
                p.relit.set(index, index + 3);
            }
        }
        if (p.fires == null)
            return;
        for (int i = 0; i < p.fires.size(); i++) {
            final long fire = p.fires.getLong(i);
            final int index = heights.getSectionIndex(BlockPos.getY(fire));
            if (index < 0 || index >= changed.length)
                continue;
            final ShortSet set = changed[index];
            if (set != null && set.remove((short) (SectionPos.sectionRelative(BlockPos.getX(fire)) << 8
                    | SectionPos.sectionRelative(BlockPos.getZ(fire)) << 4 | SectionPos.sectionRelative(BlockPos.getY(fire))))
                    && set.isEmpty()) {
                changed[index] = null;
            }
        }
    }

    /**
     * Light checks queued around the chunk are still being computed.
     */
    public static boolean lightPending(ServerLevel level, long chunk) {
        final Pending p = pending.get(chunk);
        return p != null && p.markers > 0 && p.level == level;
    }

    /**
     * The light checks queued before the markers of these chunks are computed.
     */
    static void markersDone(ServerLevel level, long[] chunks) {
        lastMarkerTick = tick;
        for (long chunk : chunks) {
            markerDone(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        }
        spillMarkersDone(level, chunks);
    }

    /**
     * The light of these chunks is computed: so is what it spilled over into their
     * neighbours, whose light change notifications are queued on the chunk source's own
     * executor, ahead of this marker. The neighbours stop waiting for these chunks after
     * those notifications, on the same executor.
     */
    private static void spillMarkersDone(ServerLevel level, long[] chunks) {
        if (spills.isEmpty())
            return;
        if (chunkExecutorAccess) {
            try {
                final BlockableEventLoop<Runnable> executor = level.getChunkSource().mainThreadProcessor;
                executor.tell(() -> spillMarkersRun(level, chunks));
                return;
            } catch (LinkageError e) {
                chunkExecutorAccess = false;
                JudgementDayMod.LOGGER.warn("[Apocalypse] Chunk executor not accessible: " + e);
            }
        }
        spillMarkersRun(level, chunks);
    }

    private static void spillMarkersRun(ServerLevel level, long[] chunks) {
        for (long chunk : chunks) {
            final int chunkX = ChunkPos.getX(chunk);
            final int chunkZ = ChunkPos.getZ(chunk);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0)
                        continue;
                    final Spill s = spills.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                    if (s == null || s.level != level)
                        continue;
                    final int out = s.waitingFor.get(chunk);
                    if (out <= 0)
                        continue;
                    if (out == 1)
                        s.waitingFor.remove(chunk);
                    else
                        s.waitingFor.addTo(chunk, -1);
                    if (s.waitingFor.isEmpty())
                        s.until = tick + SPILL_GRACE_TICKS;
                }
            }
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
        spillMarkersDone(level, chunks);
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
                    UnattendedChunkHandler.lightDone(level, key);
                }
            }
        }
    }

    /**
     * Sends again the processed chunks whose light is computed, then the light their
     * neighbours got from them. Called at the start of every server tick: the light change
     * notifications queued with the markers are in by then, so vanilla does not send them
     * a second time (see dropQueuedChanges).
     */
    public static void beforeTick() {
        if (!lit.isEmpty()) {
            resendLit();
        }
        if (!spills.isEmpty()) {
            sendSpills();
        }
        if (!followUps.isEmpty()) {
            sendFollowUps();
        }
    }

    /**
     * Light section changes of a chunk (light engine notification, server thread).
     */
    public static void sectionLightChanged(ServerLevel level, long chunk, LightLayer layer, int sectionY) {
        if (spills.isEmpty() && followUps.isEmpty())
            return;
        final int index = sectionY - level.getLightEngine().getMinLightSection();
        if (index < 0 || index >= level.getLightEngine().getLightSectionCount())
            return;
        final Spill s = spills.get(chunk);
        if (s != null && s.level == level)
            (layer == LightLayer.SKY ? s.sky : s.block).set(index);
        final FollowUp f = followUps.get(chunk);
        if (f != null && f.level == level)
            (layer == LightLayer.SKY ? f.sky : f.block).set(index);
    }

    /**
     * The chunk was just sent again whole, with the light the server has now. Blocks
     * changed by vanilla since its processing (water flowing back, ash landing) may have
     * light checks the light engine has not computed yet: vanilla tells only the players at
     * the edge of their view distance about the light they change, the others got the new
     * blocks inside the chunk and would never relight them. The light sections that change
     * in the chunk and its neighbours until those checks are computed are sent to every player
     * viewing them (see sendFollowUps).
     */
    public static void sentWhole(ServerLevel level, int chunkX, int chunkZ, BitSet relit) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final long key = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                FollowUp f = followUps.get(key);
                if (f == null || f.level != level) {
                    f = new FollowUp(level);
                    followUps.put(key, f);
                } else if (dx == 0 && dz == 0) {
                    // Its light changes so far are in the chunk sent
                    f.sky.clear();
                    f.block.clear();
                }
                if (dx == 0 && dz == 0 && relit != null) {
                    // The sections the clients relit themselves meanwhile
                    f.sky.or(relit);
                    f.block.or(relit);
                }
                f.markers++;
                f.timeoutTick = tick + MARKER_TIMEOUT_TICKS;
            }
        }
        LightChecks.afterLightFollowUp(level, chunkX, chunkZ);
    }

    /**
     * The light checks queued before these chunks were sent again whole are computed. Their
     * light change notifications are queued on the chunk source's own executor, ahead of this
     * marker: the follow-ups end after them, on the same executor.
     */
    static void followUpsDone(ServerLevel level, long[] chunks) {
        if (followUps.isEmpty() || chunks.length == 0)
            return;
        if (chunkExecutorAccess) {
            try {
                final BlockableEventLoop<Runnable> executor = level.getChunkSource().mainThreadProcessor;
                executor.tell(() -> followUpsRun(level, chunks));
                return;
            } catch (LinkageError e) {
                chunkExecutorAccess = false;
                JudgementDayMod.LOGGER.warn("[Apocalypse] Chunk executor not accessible: " + e);
            }
        }
        followUpsRun(level, chunks);
    }

    private static void followUpsRun(ServerLevel level, long[] chunks) {
        for (long chunk : chunks) {
            final int chunkX = ChunkPos.getX(chunk);
            final int chunkZ = ChunkPos.getZ(chunk);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    final FollowUp f = followUps.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                    if (f != null && f.level == level && f.markers > 0)
                        f.markers--;
                }
            }
        }
    }

    /**
     * Sends the light changed in the chunks sent again whole and their neighbours, once the
     * checks queued before they were sent are computed (or not waited for any longer).
     */
    private static void sendFollowUps() {
        ObjectIterator<Long2ObjectMap.Entry<FollowUp>> it = followUps.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<FollowUp> entry = it.next();
            final long key = entry.getLongKey();
            final FollowUp f = entry.getValue();
            if (f.markers > 0 && tick < f.timeoutTick)
                continue;
            if (!f.sky.isEmpty() || !f.block.isEmpty()) {
                final Pending p = pending.get(key);
                // Waiting to be sent whole: it gets this light with it
                if (p == null || !p.resend || p.level != f.level)
                    sendLight(f.level, key, f.sky, f.block);
            }
            it.remove();
        }
    }

    /**
     * Sends the light changed in the neighbours of processed chunks to the players that
     * vanilla does not tell (see the class comment), then forgets the neighbours whose
     * light is computed.
     */
    private static void sendSpills() {
        ObjectIterator<Long2ObjectMap.Entry<Spill>> it = spills.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Spill> entry = it.next();
            final long key = entry.getLongKey();
            final Spill s = entry.getValue();
            final boolean expired = tick > s.until;
            // Once the light of the processed chunks around it is computed: sent in one go
            // (or when it is not waited for any longer, see MARKER_TIMEOUT_TICKS)
            if ((s.waitingFor.isEmpty() || expired) && (!s.sky.isEmpty() || !s.block.isEmpty())) {
                final Pending p = pending.get(key);
                if (p != null && p.resend && p.level == s.level) {
                    // Waiting to be sent whole: it gets this light with it
                    s.sky.clear();
                    s.block.clear();
                } else if (!expired && (DayTracker.isWaiting(key) || neighbourWaiting(key))) {
                    // Not processed yet: sent whole once processed, if it changes (kept
                    // until then, in case it does not). Or a neighbour is about to be
                    // processed: its light comes in the same packet
                    s.until = Math.max(s.until, tick + SPILL_GRACE_TICKS);
                } else {
                    sendLight(s.level, key, s.sky, s.block);
                    s.sky.clear();
                    s.block.clear();
                }
            }
            if (expired)
                it.remove();
        }
    }

    private static boolean neighbourWaiting(long key) {
        final int x = ChunkPos.getX(key);
        final int z = ChunkPos.getZ(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && DayTracker.isWaiting(ChunkPos.asLong(x + dx, z + dz)))
                    return true;
            }
        }
        return false;
    }

    /**
     * Sends the changed light sections of the chunk to the players viewing it. The ones at
     * the edge of their view distance get them from vanilla too, unless they were not at
     * the edge when vanilla sent them: a light packet twice does no harm.
     */
    private static void sendLight(ServerLevel level, long key, BitSet sky, BitSet block) {
        final ChunkPos pos = new ChunkPos(key);
        final ChunkMap chunkMap = level.getChunkSource().chunkMap;
        // Chunks are sent to the players once ticking (vanilla ignores the others too)
        if (holderAccess) {
            try {
                final ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(key);
                if (holder == null || holder.getTickingChunk() == null)
                    return;
            } catch (LinkageError e) {
                holderAccess = false;
                JudgementDayMod.LOGGER.warn("[Apocalypse] Chunk holder not accessible: " + e);
            }
        }
        final List<ServerPlayer> players = chunkMap.getPlayers(pos, false);
        if (players.isEmpty())
            return;
        final ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(pos, level.getLightEngine(),
                sky, block);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }

    private static void resendLit() {
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
            UnattendedChunkHandler.lightDone(p.level, key);
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
            Generator.updateClients(chunk, p.level, p.relit);
            // Sent whole: its light changes so far are in it
            final Spill s = spills.get(key);
            if (s != null && s.level == p.level) {
                s.sky.clear();
                s.block.clear();
            }
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
        spills.clear();
        followUps.clear();
        tick = 0;
        markersWork = true;
        lastMarkerTick = 0;
    }
}
