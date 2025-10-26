package com.creatormc.judgementdaymod.events.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("generator")
    void setGenerator_JD(ChunkGenerator generator);

    @Invoker("getChunks")
    Iterable<ChunkHolder> invokeGetChunks();
}