package com.creatormc.judgementdaymod.events;


import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraftforge.eventbus.api.Event;

public class ProtoChunkGeneratedEvent extends Event {
    private final ProtoChunk chunk;
    private final ServerLevel level;
    
    public ProtoChunkGeneratedEvent(ProtoChunk chunk, ServerLevel level) {
        this.chunk = chunk;
        this.level = level;
    }
    
    public ProtoChunk getChunk() { return chunk; }
    public ServerLevel getLevel() { return level; }
}