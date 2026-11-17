package com.creatormc.judgementdaymod.events.fabric;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/** A chunk was sent to a player, where Forge fires its ChunkWatchEvent.Watch. */
public record ChunkWatchEvent(ServerPlayer getPlayer, LevelChunk getChunk, ServerLevel getLevel) {
}
