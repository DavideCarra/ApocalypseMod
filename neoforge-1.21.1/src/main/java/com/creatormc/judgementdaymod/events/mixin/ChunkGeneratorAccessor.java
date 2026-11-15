package com.creatormc.judgementdaymod.events.mixin;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor to read the protected biomeSource field from ChunkGenerator. */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
    @Accessor("biomeSource")
    BiomeSource getBiomeSource_JD();
}
