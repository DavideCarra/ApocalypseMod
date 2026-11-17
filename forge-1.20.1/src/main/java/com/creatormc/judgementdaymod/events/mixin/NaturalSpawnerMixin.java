package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once animals are extinct their natural spawns are refused (AnimalExtinctionHandler),
 * so the mob caps of their categories never fill and the spawner would try again on
 * every tick for every chunk. The attempt is skipped up front instead: same result,
 * without the work. Injection only, so other mods can change the same method.
 */
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {

    @Inject(method = "spawnCategoryForChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private static void judgementday$skipExtinctCategories(MobCategory category, ServerLevel level, LevelChunk chunk,
            NaturalSpawner.SpawnPredicate predicate, NaturalSpawner.AfterSpawnCallback callback, CallbackInfo ci) {
        if (AnimalExtinctionHandler.skipsNaturalSpawning(level, category)) {
            ci.cancel();
        }
    }
}
