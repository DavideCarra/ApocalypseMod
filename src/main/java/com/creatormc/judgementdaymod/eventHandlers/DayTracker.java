package com.creatormc.judgementdaymod.eventHandlers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
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
import net.minecraft.world.level.chunk.ChunkSource;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.ApocalypseChunkGenerator;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.Analyzer;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;

import java.lang.reflect.Field;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DayTracker {

    private static long tickCount = 0;
    private static final int TICKS_PER_DAY = 40;

    // Thread-safe queue to process chunks in the next tick
    private static final ConcurrentLinkedQueue<ChunkToProcess> chunksToProcess = new ConcurrentLinkedQueue<>();

    @SubscribeEvent(priority = EventPriority.LOW) // Low priority to ensure the chunk is fully loaded
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        // Server side only
        if (event.getLevel().isClientSide()) {
            return;
        }

        // Only for ServerLevel
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }

        ServerLevel serverLevel = event.getLevel();
        ChunkAccess chunk = event.getChunk();

        // Verify it's a complete LevelChunk (not partially generated)
        if (!(chunk instanceof LevelChunk)) {
            return;
        }

        // Do not modify the world now, just add it to the queue for later processing
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        chunksToProcess.offer(new ChunkToProcess(serverLevel, chunkX, chunkZ));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        tickCount++;

        // long dayCount = level.getDayTime() / 24000L; use something like this later

        // Clear cached burnable blocks periodically
        if (tickCount % 1000 == 0) {
            Analyzer.clearCache();
        }

        // All online players:
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (tickCount % TICKS_PER_DAY == 0) {
                int day = (int) (tickCount / TICKS_PER_DAY);
                ConfigManager.apocalypseCurrentDay++;
                ConfigManager.save();
                Generator.handleDayEvent(day, player);
            }
        }

        // Process ONLY chunks in the queue (recently loaded ones)
        ChunkToProcess chunkToProcess;
        while ((chunkToProcess = chunksToProcess.poll()) != null) {
            Generator.processChunk(chunkToProcess);
        }

    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        System.out.println("Server starting - installing Apocalypse ChunkGenerator...");
        ConfigManager.load();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.dimension() == Level.OVERWORLD) {
                replaceOverworldGenerator(level);
            }
        }
    }

    public static void enqueueChunk(ChunkToProcess c) {
        chunksToProcess.offer(c);
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
