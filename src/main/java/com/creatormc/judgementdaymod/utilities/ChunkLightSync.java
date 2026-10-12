package com.creatormc.judgementdaymod.utilities;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/**
 * Keeps client lighting aligned with the server after direct section edits.
 *
 * Chunks are re-sent to clients right after processing, but the light checks
 * queued for the edited blocks are computed later by the light thread, so the
 * packet carries the old light. Vanilla only forwards later light changes to
 * players at the edge of their view distance, so everybody else keeps the stale
 * light (also in the neighbouring chunks the light spills into).
 *
 * After the edits of a chunk a marker task is queued in the light engine right
 * behind its light checks: the engine runs it once those checks are fully
 * computed. Then the sections whose light changed in the 3x3 area around the
 * chunk are sent, once, to every player viewing them. Light changes made later
 * by normal block updates are relit by the clients themselves, as in vanilla.
 * If the marker cannot be queued (unexpected engine version), the area is
 * instead watched for WATCH_TICKS and its changes are sent once they settle.
 * Client side only: server light, and so every game mechanic, is untouched.
 *
 * Main server thread only (the marker hands back to it).
 */
public final class ChunkLightSync {

    // Ticks an area stays watched after its last marker, for notifications still queued
    private static final int LINGER_TICKS = 20;
    // Longest wait for a marker (e.g. the chunk unloaded and its tasks were dropped)
    private static final int MARKER_TIMEOUT_TICKS = 1200;

    // Fallback (no marker): ticks without new light changes before sending
    private static final int QUIET_TICKS = 20;
    // Fallback: longest wait for a chunk whose light keeps changing
    private static final int MAX_WAIT_TICKS = 100;
    // Fallback: how long a chunk stays watched after its last edit
    private static final int WATCH_TICKS = 600;

    private static final class Pending {
        final ServerLevel level;
        final BitSet skyChanged = new BitSet();
        final BitSet blockChanged = new BitSet();
        // Markers still running in the light engine for chunks of this area
        int markers;
        long watchedUntil;
        // Tick of the first change not sent yet, -1 when there is none
        long firstChangeTick = -1;
        long lastChangeTick;

        Pending(ServerLevel level) {
            this.level = level;
        }

        boolean dirty() {
            return firstChangeTick >= 0;
        }
    }

    private static final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();
    private static long tick = 0;

    // ThreadedLevelLightEngine.addTask(int, int, TaskType, Runnable) and TaskType.POST_UPDATE
    private static Method addTask;
    private static Object postUpdate;
    private static boolean markerResolved = false;

    private ChunkLightSync() {
    }

    /**
     * Light checks were just queued for the given chunk: forward the light of the 3x3
     * area around it (light spreads at most 15 blocks) once they are computed.
     */
    public static void watchArea(ServerLevel level, int chunkX, int chunkZ) {
        final boolean marker = queueMarker(level, chunkX, chunkZ);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                Pending p = pending.get(key);
                if (p == null) {
                    p = new Pending(level);
                    pending.put(key, p);
                }
                if (marker) {
                    p.markers++;
                    p.watchedUntil = tick + MARKER_TIMEOUT_TICKS;
                } else {
                    p.watchedUntil = Math.max(p.watchedUntil, tick + WATCH_TICKS);
                }
            }
        }
    }

    private static boolean queueMarker(ServerLevel level, int chunkX, int chunkZ) {
        if (!markerResolved) {
            markerResolved = true;
            try {
                Class<?> taskType = Class.forName("net.minecraft.server.level.ThreadedLevelLightEngine$TaskType");
                postUpdate = taskType.getEnumConstants()[1]; // PRE_UPDATE, POST_UPDATE
                addTask = ObfuscationReflectionHelper.findMethod(ThreadedLevelLightEngine.class, "m_9312_",
                        int.class, int.class, taskType, Runnable.class);
            } catch (Throwable t) {
                addTask = null;
                JudgementDayMod.LOGGER.warn("[Apocalypse] Light marker unavailable, using timed light sync: " + t);
            }
        }
        if (addTask == null)
            return false;

        try {
            Runnable done = () -> level.getServer().execute(() -> markerDone(chunkX, chunkZ));
            addTask.invoke(level.getChunkSource().getLightEngine(), chunkX, chunkZ, postUpdate, done);
            return true;
        } catch (Throwable t) {
            addTask = null;
            JudgementDayMod.LOGGER.warn("[Apocalypse] Light marker failed, using timed light sync: " + t);
            return false;
        }
    }

    /**
     * The light checks queued before the marker of this chunk are computed. Each chunk
     * of the area is sent once, LINGER_TICKS after its last marker (by then the light
     * change notifications, which reach the main thread through another queue, are in).
     */
    private static void markerDone(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Pending p = pending.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                if (p == null)
                    continue;
                if (p.markers > 0)
                    p.markers--;
                if (p.markers == 0)
                    p.watchedUntil = tick + LINGER_TICKS;
            }
        }
    }

    /**
     * Called (main thread) every time the server light of a chunk section changes.
     */
    public static void onSectionLightChanged(LevelLightEngine engine, ChunkPos pos, LightLayer layer, int sectionY) {
        if (pending.isEmpty())
            return;

        Pending p = pending.get(pos.toLong());
        // Same coordinates in another dimension
        if (p == null || p.level.getLightEngine() != engine)
            return;

        // Same section indexing used by ChunkHolder / ClientboundLightUpdatePacket
        int minLightSection = engine.getMinLightSection();
        int maxLightSection = engine.getMaxLightSection();
        if (sectionY < minLightSection || sectionY > maxLightSection)
            return;

        int index = sectionY - minLightSection;
        if (layer == LightLayer.SKY) {
            p.skyChanged.set(index);
        } else {
            p.blockChanged.set(index);
        }
        if (p.firstChangeTick < 0) {
            p.firstChangeTick = tick;
        }
        p.lastChangeTick = tick;
    }

    /**
     * Sends what is left of the watched chunks and forgets the finished ones.
     * Called once per server tick.
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

            if (p.markers > 0 && tick < p.watchedUntil)
                continue; // waiting for the light engine

            if (addTask != null) {
                // Marker mode: send the last notifications when the area is done
                if (tick >= p.watchedUntil) {
                    if (p.dirty())
                        send(p, key);
                    it.remove();
                }
                continue;
            }

            // Timed mode
            if (p.dirty() && (tick - p.lastChangeTick >= QUIET_TICKS || tick - p.firstChangeTick >= MAX_WAIT_TICKS)) {
                send(p, key);
            }
            if (!p.dirty() && tick >= p.watchedUntil) {
                it.remove();
            }
        }
    }

    private static void send(Pending p, long key) {
        ServerLevel level = p.level;
        ChunkPos pos = new ChunkPos(key);
        if (level.hasChunk(pos.x, pos.z)) {
            List<ServerPlayer> viewers = level.getChunkSource().chunkMap.getPlayers(pos, false);
            if (!viewers.isEmpty()) {
                ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(pos, level.getLightEngine(),
                        p.skyChanged, p.blockChanged);
                for (ServerPlayer viewer : viewers) {
                    viewer.connection.send(packet);
                }
            }
        }
        p.skyChanged.clear();
        p.blockChanged.clear();
        p.firstChangeTick = -1;
    }

    public static void clear() {
        pending.clear();
        tick = 0;
    }
}
