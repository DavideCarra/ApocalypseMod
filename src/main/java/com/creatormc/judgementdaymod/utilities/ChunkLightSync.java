package com.creatormc.judgementdaymod.utilities;

import java.util.BitSet;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;

/**
 * Keeps client lighting aligned with the server after direct section edits.
 *
 * Chunks are re-sent to clients right after processing, but the light checks
 * queued for the removed water are computed later by the light thread, so the
 * packet carries the old light. Vanilla only forwards later light changes to
 * players at the edge of their view distance, so everybody else keeps the stale
 * light (also in the neighbouring chunks the light spills into).
 *
 * This class collects the sections whose light changed in the touched area and,
 * once the light engine has gone quiet for that chunk, sends one light update
 * packet with exactly those sections to every player viewing it.
 * Client side only: server light, and so every game mechanic, is untouched.
 *
 * Main server thread only.
 */
public final class ChunkLightSync {

    // Ticks without new light changes before the light of a chunk is considered settled
    private static final int QUIET_TICKS = 5;
    // Upper bound to wait for a chunk whose light keeps changing (e.g. burning areas)
    private static final int MAX_WAIT_TICKS = 100;

    private static final class Pending {
        final ServerLevel level;
        final BitSet skyChanged = new BitSet();
        final BitSet blockChanged = new BitSet();
        final long firstTick;
        long lastChangeTick;

        Pending(ServerLevel level, long now) {
            this.level = level;
            this.firstTick = now;
            this.lastChangeTick = now;
        }
    }

    private static final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();
    private static long tick = 0;

    private ChunkLightSync() {
    }

    /**
     * Starts collecting light changes for the 3x3 chunk area centred on the given
     * chunk (light spreads at most 15 blocks, so it never goes past the neighbours).
     */
    public static void watchArea(ServerLevel level, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                Pending p = pending.get(key);
                if (p == null) {
                    pending.put(key, new Pending(level, tick));
                } else {
                    // New light checks were just queued: wait for them too
                    p.lastChangeTick = tick;
                }
            }
        }
    }

    /**
     * Called (main thread) every time the server light of a chunk section changes.
     */
    public static void onSectionLightChanged(ChunkPos pos, LightLayer layer, int sectionY) {
        if (pending.isEmpty())
            return;

        Pending p = pending.get(pos.toLong());
        if (p == null)
            return;

        // Same section indexing used by ChunkHolder / ClientboundLightUpdatePacket
        int minLightSection = p.level.getLightEngine().getMinLightSection();
        int maxLightSection = p.level.getLightEngine().getMaxLightSection();
        if (sectionY < minLightSection || sectionY > maxLightSection)
            return;

        int index = sectionY - minLightSection;
        if (layer == LightLayer.SKY) {
            p.skyChanged.set(index);
        } else {
            p.blockChanged.set(index);
        }
        p.lastChangeTick = tick;
    }

    /**
     * Sends the settled light of the watched chunks. Called once per server tick.
     */
    public static void tick() {
        tick++;
        if (pending.isEmpty())
            return;

        ObjectIterator<Long2ObjectMap.Entry<Pending>> it = pending.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Pending> entry = it.next();
            Pending p = entry.getValue();

            boolean hasChanges = !p.skyChanged.isEmpty() || !p.blockChanged.isEmpty();
            boolean settled = hasChanges && tick - p.lastChangeTick >= QUIET_TICKS;
            boolean expired = tick - p.firstTick >= MAX_WAIT_TICKS;
            if (!settled && !expired)
                continue;

            it.remove();
            if (hasChanges) {
                send(p, new ChunkPos(entry.getLongKey()));
            }
        }
    }

    private static void send(Pending p, ChunkPos pos) {
        ServerLevel level = p.level;
        if (!level.hasChunk(pos.x, pos.z))
            return;

        List<ServerPlayer> viewers = level.getChunkSource().chunkMap.getPlayers(pos, false);
        if (viewers.isEmpty())
            return;

        ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(pos, level.getLightEngine(),
                p.skyChanged, p.blockChanged);
        for (ServerPlayer viewer : viewers) {
            viewer.connection.send(packet);
        }
    }

    public static void clear() {
        pending.clear();
        tick = 0;
    }
}
