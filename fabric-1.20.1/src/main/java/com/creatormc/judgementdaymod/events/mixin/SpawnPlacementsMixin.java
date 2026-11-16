package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The spawn placement check of natural spawning and world generation: an animal that may
 * not appear any more fails it, as Forge's SpawnPlacementCheck event makes it fail.
 */
@Mixin(SpawnPlacements.class)
public class SpawnPlacementsMixin {

    @Inject(method = "checkSpawnRules", at = @At("RETURN"), cancellable = true)
    private static void judgementday$onCheckSpawnRules(EntityType<?> type, ServerLevelAccessor level,
            MobSpawnType spawnType, BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> cir) {
        if (AnimalExtinctionHandler.refusesPlacement(type, level, spawnType))
            cir.setReturnValue(false);
    }
}
