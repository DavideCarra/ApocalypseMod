package com.creatormc.judgementdaymod.handlers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.server.MinecraftServer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import com.creatormc.judgementdaymod.models.ApocalypseSyncPacket;
import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.Analyzer;
import com.creatormc.judgementdaymod.utilities.ChunkLightSync;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;
import com.creatormc.judgementdaymod.utilities.LightChecks;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DayTracker {

    private static long tickCount = 0;
    private static long lastDayTime = -1;
    private static final int CHUNKS_PER_TICK = 16;

    // Thread-safe queue to process chunks in the next tick
    private static final ConcurrentLinkedQueue<ChunkToProcess> chunksToProcess = new ConcurrentLinkedQueue<>();
    // Chunks of a batch left for the next tick, processed before the queue (server thread)
    private static final ArrayDeque<ChunkToProcess> carriedOver = new ArrayDeque<>();
    // How many times each chunk waits to be processed (queue and carried over)
    private static final Long2IntOpenHashMap waiting = new Long2IntOpenHashMap();

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        // Solo lato server
        if (event.getLevel().isClientSide()) {
            return;
        }
        ServerLevel serverLevel = event.getLevel();
        ChunkAccess chunkAccess = event.getChunk();
        if (!(chunkAccess instanceof LevelChunk chunk)) {
            return;
        }

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Inserisci chunk in the list
        ChunkToProcess task = new ChunkToProcess(serverLevel, chunkX, chunkZ);
        offer(task);

    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // Processed chunks whose light is computed (see ChunkLightSync)
            ChunkLightSync.beforeTick();
            return;
        }
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        tickCount++;

        // Send pending light corrections to clients (see ChunkLightSync)
        ChunkLightSync.tick();

        // Clear cached burnable blocks periodically
        if (tickCount % 1000 == 0) {
            Analyzer.clearCache();
        }

        // The day counts in the overworld, also while nobody is online (all levels share
        // its time of day)
        final ServerLevel overworld = server.overworld();
        if (overworld != null) {
            long currentDayTime = overworld.getDayTime() % 24000L; // Time of day in ticks (0–23999)

            // First tick after server start — initialize reference value
            if (lastDayTime == -1) {
                lastDayTime = currentDayTime;
                return;
            }

            // Detect when day time "wraps around" (from 23999 back to 0)
            // This happens when a full day passes, or when players sleep or use /time set
            // day
            if (currentDayTime < lastDayTime) {
                ConfigManager.apocalypseCurrentDay++;
                ConfigManager.save();
                UnattendedChunkHandler.onNewDay(overworld);
                Generator.handleDayEvent();

                // Every player draws the new day
                NetworkHandler.sendToAll(ApocalypseSyncPacket.current());
            }

            // Update stored value for the next tick comparison
            lastDayTime = currentDayTime;
        }

        // Nothing queued: skip the batch allocation
        if (chunksToProcess.isEmpty() && carriedOver.isEmpty())
            return;

        // --- PROCESS MULTI-CHUNK BATCH PER TICK + SHUFFLE ---
        // Pull up to N tasks, the ones left by the last tick first
        List<ChunkToProcess> batchToProcesses = new ArrayList<>(CHUNKS_PER_TICK);

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            ChunkToProcess next = carriedOver.isEmpty() ? chunksToProcess.poll() : carriedOver.poll();
            if (next == null)
                break;
            batchToProcesses.add(next);
        }

        if (!batchToProcesses.isEmpty()) {
            // Randomize processing order to avoid visible sweeping lines
            // Collections.shuffle(batch, ThreadLocalRandom.current());
            Collections.shuffle(batchToProcesses, ThreadLocalRandom.current());

            // Process batch (their light checks are queued together at the end, after the
            // falling ash of their catch ups has landed)
            LightChecks.begin();
            Generator.beginBatch();
            try {
                // At most the work of N chunks processed on time: a catch up of many steps
                // counts as several chunks, the rest of the batch waits for the next tick
                float work = 0;
                for (int i = 0; i < batchToProcesses.size(); i++) {
                    if (work >= CHUNKS_PER_TICK) {
                        carriedOver.addAll(batchToProcesses.subList(i, batchToProcesses.size()));
                        break;
                    }
                    ChunkToProcess task = batchToProcesses.get(i);
                    leftQueue(task);
                    ServerLevel lvl = task.getLevel();
                    if (lvl == null || !lvl.getServer().isSameThread()
                            || !lvl.hasChunk(task.getChunkX(), task.getChunkZ())) {
                        work++;
                        continue;
                    }

                    // Do the work
                    work += Generator.processChunk(task);
                }
            } finally {
                Generator.endBatch();
                LightChecks.end();
            }
        }

    }

    /** A player joining draws the apocalypse as the server has it. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.sendToClient(ApocalypseSyncPacket.current(), player);
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ConfigManager.load();

        // Reset variables
        tickCount = 0;
        lastDayTime = -1;
        chunksToProcess.clear();
        carriedOver.clear();
        synchronized (waiting) {
            waiting.clear();
        }
        ChunkLightSync.clear();
        LightChecks.clear();
        Generator.clearPendingFalls();
        Generator.resetState();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Drop references to the stopped world so it can be garbage collected
        chunksToProcess.clear();
        carriedOver.clear();
        synchronized (waiting) {
            waiting.clear();
        }
        ChunkLightSync.clear();
        LightChecks.clear();
        Generator.clearPendingFalls();
    }

    public static void enqueueChunk(ChunkToProcess c) {
        offer(c);
    }


    private static void offer(ChunkToProcess task) {
        if (isOverworld(task)) {
            synchronized (waiting) {
                waiting.addTo(ChunkPos.asLong(task.getChunkX(), task.getChunkZ()), 1);
            }
        }
        chunksToProcess.offer(task);
    }

    private static void leftQueue(ChunkToProcess task) {
        if (!isOverworld(task))
            return;
        final long key = ChunkPos.asLong(task.getChunkX(), task.getChunkZ());
        synchronized (waiting) {
            if (waiting.addTo(key, -1) <= 1) {
                waiting.remove(key);
            }
        }
    }

    // Only overworld chunks are processed
    private static boolean isOverworld(ChunkToProcess task) {
        return task.getLevel() != null && task.getLevel().dimension() == Level.OVERWORLD;
    }

    /**
     * True if the overworld chunk waits to be processed (it is sent again whole once
     * processed, if it changes).
     */
    public static boolean isWaiting(long chunk) {
        synchronized (waiting) {
            return waiting.containsKey(chunk);
        }
    }
}
