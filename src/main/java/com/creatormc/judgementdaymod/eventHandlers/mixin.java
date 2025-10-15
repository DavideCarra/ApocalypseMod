/*package com.creatormc.judgementdaymod.eventHandlers;

import com.creatormc.judgementdaymod.ApocalypseChunkGenerator;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.ChunkStatusUpdateListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ChunkMap.class)
public class ChunkMapMixin {
    
    @Shadow
    private ChunkGenerator generator;
    
    @Inject(method = "<init>", at = @At("TAIL"))
    private void replaceGenerator(ServerLevel level, 
                                LevelStorageSource.LevelStorageAccess storage,
                                DataFixer dataFixer, 
                                StructureTemplateManager templateManager,
                                Executor executor, 
                                BlockableEventLoop<Runnable> mainThread,
                                LightChunkGetter lightChunkGetter, 
                                ChunkProgressListener progressListener,
                                ChunkStatusUpdateListener statusUpdateListener, 
                                Supplier<DimensionDataStorage> storage2,
                                int viewDistance, 
                                boolean sync, 
                                CallbackInfo ci) {
        
        // Sostituisci solo nell'Overworld
        if (level.dimension() == Level.OVERWORLD) {
            ChunkGenerator originalGenerator = this.generator;
            this.generator = new ApocalypseChunkGenerator(originalGenerator, originalGenerator.getBiomeSource());
            
            System.out.println("✅ Apocalypse ChunkGenerator installed for Overworld via Mixin!");
        }
    }
}*/