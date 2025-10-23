package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.setup.ApocalypseChunkGenerator;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Executor;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    @SuppressWarnings("resource")
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(MinecraftServer server,
            Executor executor,
            LevelStorageSource.LevelStorageAccess storageAccess,
            ServerLevelData levelData,
            ResourceKey<Level> dimension,
            LevelStem levelStem,
            ChunkProgressListener progressListener,
            boolean isDebug,
            long seed,
            List<?> spawners,
            boolean tickTime,
            RandomSequences randomSequences,
            CallbackInfo ci) {

        if (dimension != Level.OVERWORLD)
            return;

        try {
            JudgementDayMod.LOGGER
                    .info("[JudgementDayMixin] Attempting to install ApocalypseChunkGenerator for Overworld...");

            // Access the real server chunk cache
            ServerLevel level = (ServerLevel) (Object) this;
            ServerChunkCache serverChunkCache = (ServerChunkCache) level.getChunkSource();
            ChunkGenerator originalGenerator = serverChunkCache.getGenerator();

            // Avoid wrapping twice
            if (originalGenerator instanceof ApocalypseChunkGenerator) {
                JudgementDayMod.LOGGER.info("[JudgementDayMixin] ApocalypseChunkGenerator already active, skipping.");
                return;
            }

            // Create the Apocalypse generator wrapper
            ApocalypseChunkGenerator apocalypseGenerator = new ApocalypseChunkGenerator(originalGenerator,
                    ((ChunkGeneratorAccessor) originalGenerator).getBiomeSource_JD());

            // Replace the private generator field in ChunkMap
            ChunkMap chunkMap = ((ServerChunkCacheAccessor) serverChunkCache).getChunkMap_JD();
            ((ChunkMapAccessor) chunkMap).setGenerator_JD(apocalypseGenerator);

            JudgementDayMod.LOGGER.info("[JudgementDayMixin] ApocalypseChunkGenerator successfully installed!");

        } catch (Exception e) {
            System.err.println("[JudgementDayMixin] Failed to install ApocalypseChunkGenerator:");
            e.printStackTrace();
        }
    }
}
