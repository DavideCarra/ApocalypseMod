package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.utilities.Generator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * From the first step of the apocalypse on, snow and ice melt in the open (see
 * Generator.transformSurface): no new snow settles and no water freezes in the
 * overworld any more, neither while it snows nor in the chunks generated then.
 * Injections only, so other mods can change the same methods.
 */
@Mixin(Biome.class)
public class BiomeMixin {

    @Inject(method = "shouldSnow", at = @At("HEAD"), cancellable = true, require = 0)
    private void judgementday$noSnow(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (Generator.snowMelts(level)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void judgementday$noIce(LevelReader level, BlockPos pos, boolean mustBeAtEdge,
            CallbackInfoReturnable<Boolean> cir) {
        if (Generator.snowMelts(level)) {
            cir.setReturnValue(false);
        }
    }
}
