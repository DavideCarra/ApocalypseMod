package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.utilities.ChunkLightSync;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells ChunkLightSync which light sections of a chunk changed, so that the light
 * spilling over from a processed neighbour reaches the players vanilla does not tell,
 * and lets it leave out the changes at the fires that do not glow of a chunk about to be
 * sent again whole (see ChunkLightSync.beforeBroadcast).
 */
@Mixin(ChunkHolder.class)
public class ChunkHolderMixin {

    @Shadow
    @Final
    private LevelHeightAccessor levelHeightAccessor;

    @Inject(method = "broadcastChanges", at = @At("HEAD"), require = 0)
    private void judgementday$beforeBroadcast(LevelChunk chunk, CallbackInfo ci) {
        // Fields opened by the access transformer
        final ChunkHolder self = (ChunkHolder) (Object) this;
        if (self.hasChangedSections && levelHeightAccessor instanceof ServerLevel level) {
            ChunkLightSync.beforeBroadcast(level, ((ChunkHolder) (Object) this).getPos().toLong(), self.changedBlocksPerSection, levelHeightAccessor);
        }
    }

    @Inject(method = "sectionLightChanged", at = @At("HEAD"), require = 0)
    private void judgementday$onSectionLightChanged(LightLayer layer, int sectionY, CallbackInfo ci) {
        if (levelHeightAccessor instanceof ServerLevel level) {
            ChunkLightSync.sectionLightChanged(level, ((ChunkHolder) (Object) this).getPos().toLong(), layer, sectionY);
        }
    }
}
