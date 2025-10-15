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
import com.creatormc.judgementdaymod.utilities.Analyzer;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;

import com.creatormc.judgementdaymod.ApocalypseChunkGenerator;

import java.lang.reflect.Field;

@Mod.EventBusSubscriber(modid = "judgementday", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DayTracker {

    private static long tickCount = 0;
    private static final int TICKS_PER_DAY = 40;

    // Coda thread-safe per processare i chunk nel tick successivo
    private static final ConcurrentLinkedQueue<ChunkToProcess> chunksToProcess = new ConcurrentLinkedQueue<>();

    @SubscribeEvent(priority = EventPriority.LOW) // Bassa priorità per essere sicuri che il chunk sia pronto
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        // Solo lato server
        if (event.getLevel().isClientSide()) {
            return;
        }

        // Solo per ServerLevel
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }

        ServerLevel serverLevel = event.getLevel();
        ChunkAccess chunk = event.getChunk();

        // Verifica che sia un LevelChunk completo (non solo generazione parziale)
        if (!(chunk instanceof LevelChunk)) {
            return;
        }

        // Non modificare il mondo ora, ma aggiungi alla coda per processare dopo
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

        if (tickCount % 100 == 0) {
            System.out.println(tickCount);
        }
        
        //ripulisco la cache dei blocchi bruciabili settati
        if (tickCount % 1000 == 0) {
            Analyzer.clearCache();
        }

        // Tutti i player online:
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (tickCount % TICKS_PER_DAY == 0) {
                int day = (int) (tickCount / TICKS_PER_DAY);
                Generator.handleDayEvent(day, player);
            }
        }

        // Processa SOLO i chunk nella coda (quelli appena caricati)
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

    private static void replaceOverworldGenerator(ServerLevel level) {
        try {
            // Cast a ServerChunkCache invece di ChunkSource
            ServerChunkCache serverChunkCache = (ServerChunkCache) level.getChunkSource();
            ChunkGenerator originalGenerator = serverChunkCache.getGenerator();

            // Crea il tuo wrapper generator
            ApocalypseChunkGenerator apocalypseGenerator = new ApocalypseChunkGenerator(
                    originalGenerator,
                    originalGenerator.getBiomeSource());

            // Usa reflection per sostituire il generator nella ChunkMap
            Field chunkMapField = ServerChunkCache.class.getDeclaredField("chunkMap");
            chunkMapField.setAccessible(true);
            ChunkMap chunkMap = (ChunkMap) chunkMapField.get(serverChunkCache);

            Field generatorField = ChunkMap.class.getDeclaredField("generator");
            generatorField.setAccessible(true);
            generatorField.set(chunkMap, apocalypseGenerator);

            System.out.println("✅ Apocalypse ChunkGenerator installed for Overworld!");

        } catch (Exception e) {
            System.err.println("❌ Failed to install Apocalypse ChunkGenerator:");
            e.printStackTrace();
        }
    }

}