package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bee nests placed on new trees (e.g. saplings grown after the apocalypse) come
 * with brand new bees: once animals are extinct the nest is still placed, empty
 * (see BeehiveBlockEntityMixin). Bees that players keep in their hives are not
 * affected. Injections only, so other mods can change the same methods.
 */
@Mixin(BeehiveDecorator.class)
public class BeehiveDecoratorMixin {

    @Inject(method = "place", at = @At("HEAD"), require = 0)
    private void judgementday$onPlaceStart(TreeDecorator.Context context, CallbackInfo ci) {
        boolean extinct = AnimalExtinctionHandler.isExtinct()
                && context.level() instanceof WorldGenLevel level
                && AnimalExtinctionHandler.isExtinctIn(level.getLevel());
        AnimalExtinctionHandler.EMPTY_NEW_NESTS.set(extinct);
    }

    @Inject(method = "place", at = @At("RETURN"), require = 0)
    private void judgementday$onPlaceEnd(TreeDecorator.Context context, CallbackInfo ci) {
        AnimalExtinctionHandler.EMPTY_NEW_NESTS.set(false);
    }
}
