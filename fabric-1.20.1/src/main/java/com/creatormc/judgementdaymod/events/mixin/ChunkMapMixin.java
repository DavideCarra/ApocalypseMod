package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.ModEvents;

import org.apache.commons.lang3.mutable.MutableObject;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chunk was sent to a player, where Forge fires its ChunkWatchEvent.Watch (end of playerLoadedChunk). */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "playerLoadedChunk", at = @At("TAIL"))
    private void judgementday$onPlayerLoadedChunk(ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> packet, LevelChunk chunk, CallbackInfo ci) {
        ModEvents.chunkWatch(player, chunk, level);
    }
}
