package com.creatormc.judgementdaymod.events.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Since 1.21 the chunk map keeps its generator in its world generation context, read
 * again by every generation step: the context is replaced with one holding the
 * apocalypse generator (see ServerLevelMixin).
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("worldGenContext")
    WorldGenContext getWorldGenContext_JD();

    @Mutable
    @Accessor("worldGenContext")
    void setWorldGenContext_JD(WorldGenContext context);
}
