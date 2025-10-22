package com.creatormc.judgementdaymod.models;

import net.minecraft.server.level.ServerLevel;

public class ChunkToProcess {
    final ServerLevel level;
    final int chunkX;
    final int chunkZ;
    private boolean requiresLightFix = true;

    public ChunkToProcess(ServerLevel level, int chunkX, int chunkZ) {
        this.level = level;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public boolean isRequiresLightFix() {
        return requiresLightFix;
    }

    public void setRequiresLightFix(boolean requiresLightFix) {
        this.requiresLightFix = requiresLightFix;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }
}