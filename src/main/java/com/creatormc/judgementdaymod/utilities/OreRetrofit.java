package com.creatormc.judgementdaymod.utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.creatormc.judgementdaymod.setup.ApocalypseChunkGenerator;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Older versions generated the chunks of an active apocalypse without their decoration,
 * so without ores (see ApocalypseChunkGenerator). When such a chunk is seen and players
 * only went by it, its ores are placed as its decoration would have placed them: it ends
 * up like a chunk generated now. Done once: the chunk has its ores afterwards.
 */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OreRetrofit {
    // Work of placing the ores of a chunk, in chunks processed (see DayTracker)
    public static final float WORK = 6f;
    // Players spent at most this long around the chunk (ticks): they only went by it
    static final long MAX_INHABITED_TIME = 2 * 60 * 20;

    // Ores only the decoration places (the ore veins of the terrain place copper ore and
    // deepslate iron ore): a chunk with none of them was generated without it
    private static final Set<Block> DECORATION_ORES = Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);

    // Chunks looked at since the server started (server thread)
    private static final LongOpenHashSet checked = new LongOpenHashSet();
    // Chunks without ores waiting for their neighbours or the features (server thread)
    private static final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    // The features are ready for the server thread (see prepare)
    private static volatile boolean ready;
    private static final AtomicBoolean preparing = new AtomicBoolean();

    private OreRetrofit() {
    }

    /**
     * Looks at a chunk players see (overworld, server thread), the first time this session.
     *
     * @return true if its ores were placed now
     */
    public static boolean onSeen(ServerLevel level, LevelChunk chunk) {
        final long key = chunk.getPos().toLong();
        if (!checked.add(key))
            return false;
        final ApocalypseChunkData data = ApocalypseChunkData.get(level);
        // A neighbour's ores reaching into a chunk without them do not make it decorated
        if (!data.isOresDue(key) && hasDecorationOres(chunk))
            return false;
        if (!untouched(chunk)) {
            // Players only stay longer around it from now on: never placed
            data.setOresDue(key, false);
            return false;
        }
        if (place(level, chunk))
            return true;
        pending.add(key);
        return false;
    }

    /** One of the chunks waiting (server thread, every tick). */
    public static void tick(ServerLevel level) {
        if (pending.isEmpty())
            return;
        final long key = pending.removeFirstLong();
        final LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk == null) {
            checked.remove(key);
        } else if (!place(level, chunk)) {
            // Still waiting: back of the line
            pending.add(key);
        }
    }

    /** True if the chunk holds an ore only the decoration places (palettes only: cheap). */
    private static boolean hasDecorationOres(LevelChunk chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir() && section.maybeHas(state -> DECORATION_ORES.contains(state.getBlock())))
                return true;
        }
        return false;
    }

    /**
     * True if players only went by the chunk: they spent little time around it and left
     * nothing in it (chests, furnaces, beds, signs... all have a block entity).
     */
    private static boolean untouched(LevelChunk chunk) {
        return chunk.getInhabitedTime() <= MAX_INHABITED_TIME && chunk.getBlockEntities().isEmpty();
    }

    /**
     * Places the ores of the chunk, once its neighbours are loaded (ores reach into them,
     * as in a vanilla decoration, but only into the ones players only went by too).
     *
     * @return false if a neighbour is not loaded yet (or the features not sorted yet)
     */
    private static boolean place(ServerLevel level, LevelChunk chunk) {
        if (!(level.getChunkSource().getGenerator() instanceof ApocalypseChunkGenerator generator))
            return true;
        if (!ready) {
            if (preparing.compareAndSet(false, true))
                prepare(generator);
            return false;
        }
        final ChunkPos center = chunk.getPos();
        final List<ChunkAccess> around = new ArrayList<>(9);
        final boolean[] writable = new boolean[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final LevelChunk c = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                if (c == null)
                    return false;
                writable[around.size()] = c == chunk || untouched(c);
                around.add(c);
            }
        }
        // The neighbours without ores keep theirs to place: the ores placed now reach into them
        final ApocalypseChunkData data = ApocalypseChunkData.get(level);
        for (int i = 0; i < 9; i++) {
            final LevelChunk c = (LevelChunk) around.get(i);
            if (i != 4 && writable[i] && !hasDecorationOres(c))
                data.setOresDue(c.getPos().toLong(), true);
        }
        data.setOresDue(center.toLong(), false);

        final boolean[] written = new boolean[9];
        try {
            generator.placeOres(new OreRegion(level, around, center, writable, written), chunk);
        } catch (Exception e) {
            JudgementDayMod.LOGGER.error("[Apocalypse] Could not place the ores of chunk " + center, e);
        }
        for (int i = 0; i < 9; i++) {
            if (written[i]) {
                final LevelChunk c = (LevelChunk) around.get(i);
                // Ores are written straight into the sections (they replace stone, which
                // blocks the light as they do): the chunk must be saved and sent again
                c.setUnsaved(true);
                Generator.updateClients(c, level);
            }
        }
        return true;
    }

    /**
     * Sorts the features of the decoration on a world generation thread (it takes a
     * while): the server thread does not wait for it.
     */
    private static void prepare(ApocalypseChunkGenerator generator) {
        CompletableFuture.runAsync(() -> {
            try {
                generator.sortFeatures();
            } catch (Exception e) {
                JudgementDayMod.LOGGER.warn("[Apocalypse] Could not sort the features", e);
            } finally {
                ready = true;
            }
        }, Util.backgroundExecutor());
    }

    /** The decoration area of a loaded chunk: writes only into the chunks allowed. */
    private static final class OreRegion extends WorldGenRegion {
        private final ChunkPos first;
        private final boolean[] writable;
        private final boolean[] written;

        OreRegion(ServerLevel level, List<ChunkAccess> around, ChunkPos center, boolean[] writable, boolean[] written) {
            super(level, around, ChunkStatus.FULL, 1);
            this.first = new ChunkPos(center.x - 1, center.z - 1);
            this.writable = writable;
            this.written = written;
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            final int dx = SectionPos.blockToSectionCoord(pos.getX()) - first.x;
            final int dz = SectionPos.blockToSectionCoord(pos.getZ()) - first.z;
            if (dx < 0 || dx > 2 || dz < 0 || dz > 2 || pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight())
                return false;
            final int i = dx + dz * 3;
            if (!writable[i])
                return false;
            written[i] = true;
            return true;
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        // A chunk left waiting is looked at again when it comes back
        if (!pending.isEmpty() && event.getLevel() instanceof Level level && level.dimension() == Level.OVERWORLD
                && pending.remove(event.getChunk().getPos().toLong())) {
            checked.remove(event.getChunk().getPos().toLong());
        }
    }

    public static void clear() {
        checked.clear();
        pending.clear();
        ready = false;
        preparing.set(false);
    }
}
