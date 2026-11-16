package com.creatormc.judgementdaymod.events.fabric;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;

/** A chunk of a server level is unloaded (Forge's ChunkEvent.Unload). */
public record ChunkUnloadEvent(LevelAccessor getLevel, ChunkAccess getChunk) {
}
