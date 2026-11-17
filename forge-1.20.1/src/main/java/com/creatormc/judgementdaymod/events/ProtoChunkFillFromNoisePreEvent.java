package com.creatormc.judgementdaymod.events;

import net.minecraftforge.eventbus.api.Event;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class ProtoChunkFillFromNoisePreEvent extends Event {
    private final ProtoChunk proto;
    private final ResourceKey<Level> dimension;

    public ProtoChunkFillFromNoisePreEvent(ProtoChunk proto, ResourceKey<Level> dimension) {
        this.proto = proto;
        this.dimension = dimension;
    }

    public ProtoChunk getProto() { return proto; }
    public ResourceKey<Level> getDimension() { return dimension; }
}