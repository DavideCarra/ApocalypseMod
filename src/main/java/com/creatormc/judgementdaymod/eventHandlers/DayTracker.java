package com.creatormc.judgementdaymod.eventHandlers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.ApocalypseChunkGenerator;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.Analyzer;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;
import com.creatormc.judgementdaymod.utilities.LightFixer;

import java.lang.reflect.Field;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DayTracker {

    private static long tickCount = 0;
    private static long lastDayTime = -1;
    private static final int CHUNKS_PER_TICK = 16;
    private static final int CHUNKS_LIGHT_PER_TICK = 16;

    // Thread-safe queue to process chunks in the next tick
    private static final ConcurrentLinkedQueue<ChunkToProcess> chunksToProcess = new ConcurrentLinkedQueue<>();

    // Thread-safe queue to process lights in the next tick
    private static final ConcurrentLinkedQueue<ChunkToProcess> chunksToLightUpdate = new ConcurrentLinkedQueue<>();

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
        chunksToProcess.offer(task);
        chunksToLightUpdate.offer(task);

    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        tickCount++;

        // Clear cached burnable blocks periodically
        if (tickCount % 1000 == 0) {
            Analyzer.clearCache();
        }

        // All online players:
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();

            long currentDayTime = level.getDayTime() % 24000L; // Time of day in ticks (0–23999)

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
                Generator.handleDayEvent(player);
            }

            // Update stored value for the next tick comparison
            lastDayTime = currentDayTime;
        }

        // --- PROCESS MULTI-CHUNK BATCH PER TICK + SHUFFLE ---
        // Pull up to N tasks from the queue
        List<ChunkToProcess> batchToProcesses = new ArrayList<>(CHUNKS_PER_TICK);
        List<ChunkToProcess> batchToFixLight = new ArrayList<>(CHUNKS_LIGHT_PER_TICK);

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            ChunkToProcess next = chunksToProcess.poll();
            if (next == null)
                break;
            batchToProcesses.add(next);
        }

        for (int i = 0; i < CHUNKS_LIGHT_PER_TICK; i++) {
            ChunkToProcess next = chunksToLightUpdate.poll();
            if (next == null)
                break;
            batchToFixLight.add(next);
        }

        if (!batchToProcesses.isEmpty()) {
            // Randomize processing order to avoid visible sweeping lines
            // Collections.shuffle(batch, ThreadLocalRandom.current());
            Collections.shuffle(batchToProcesses, ThreadLocalRandom.current());

            // Process batch
            for (ChunkToProcess task : batchToProcesses) {
                ServerLevel lvl = task.getLevel();
                if (lvl == null || !lvl.getServer().isSameThread()
                        || !lvl.hasChunk(task.getChunkX(), task.getChunkZ())) {
                    continue;
                }

                // Do the work
                Generator.processChunk(task);
                chunksToLightUpdate.offer(task);
            }
        }

        if (!batchToFixLight.isEmpty()) {
            for (ChunkToProcess task : batchToFixLight) {
                ServerLevel lvl = task.getLevel();
                if (lvl == null || !lvl.getServer().isSameThread()
                        || !lvl.hasChunk(task.getChunkX(), task.getChunkZ())) {
                    continue;
                }
                // fix the lights
                LevelChunk chunk = lvl.getChunk(task.getChunkX(), task.getChunkZ());
                LightFixer.forceLightUpdate(lvl, chunk);
            }
        }

    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        System.out.println("Server starting - installing Apocalypse ChunkGenerator...");
        ConfigManager.load();

        // Reset variables
        tickCount = 0;
        lastDayTime = -1;
        chunksToProcess.clear();
        Generator.resetState();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.dimension() == Level.OVERWORLD) {
                replaceOverworldGenerator(level);
            }
        }
    }

    public static void enqueueChunk(ChunkToProcess c) {
        chunksToProcess.offer(c);
    }

    public static void enqueueLightUpdateChunk(ChunkToProcess c) {
        chunksToLightUpdate.offer(c);
    }

    private static void replaceOverworldGenerator(ServerLevel level) {
        try {
            // Cast to ServerChunkCache instead of ChunkSource
            ServerChunkCache serverChunkCache = (ServerChunkCache) level.getChunkSource();
            ChunkGenerator originalGenerator = serverChunkCache.getGenerator();

            // Create your custom generator wrapper
            ApocalypseChunkGenerator apocalypseGenerator = new ApocalypseChunkGenerator(
                    originalGenerator,
                    originalGenerator.getBiomeSource());

            // Use reflection to replace the generator inside the ChunkMap
            Field chunkMapField = ServerChunkCache.class.getDeclaredField("chunkMap");
            chunkMapField.setAccessible(true);
            ChunkMap chunkMap = (ChunkMap) chunkMapField.get(serverChunkCache);

            Field generatorField = ChunkMap.class.getDeclaredField("generator");
            generatorField.setAccessible(true);
            generatorField.set(chunkMap, apocalypseGenerator);

            System.out.println("Apocalypse ChunkGenerator installed for Overworld!");

        } catch (Exception e) {
            System.err.println("Failed to install Apocalypse ChunkGenerator:");
            e.printStackTrace();
        }
    }

}
