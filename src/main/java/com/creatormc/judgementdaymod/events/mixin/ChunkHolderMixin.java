package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.utilities.ChunkLightSync;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes server light changes (read only, vanilla logic is untouched) so that
 * ChunkLightSync can forward the final light of processed chunks to clients.
 */
@Mixin(ChunkHolder.class)
public class ChunkHolderMixin {

    @Inject(method = "sectionLightChanged", at = @At("HEAD"))
    private void judgementday$onSectionLightChanged(LightLayer layer, int sectionY, CallbackInfo ci) {
        ChunkLightSync.onSectionLightChanged(((ChunkHolder) (Object) this).getPos(), layer, sectionY);
    }
}
