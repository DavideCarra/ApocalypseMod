package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the new bees a bee nest decorator puts in its nest while animals are
 * extinct (see BeehiveDecoratorMixin). Any other bee stored in a hive is kept.
 */
@Mixin(BeehiveBlockEntity.class)
public class BeehiveBlockEntityMixin {

    @Inject(method = "storeBee", at = @At("HEAD"), cancellable = true, require = 0)
    private void judgementday$onStoreBee(CompoundTag bee, int ticksInHive, boolean hasNectar, CallbackInfo ci) {
        if (AnimalExtinctionHandler.EMPTY_NEW_NESTS.get()) {
            ci.cancel();
        }
    }

    /**
     * A real bee entering a hive is never a new nest bee: also clears the flag in case
     * a decorator stopped half way (an exception skips its RETURN injection).
     */
    @Inject(method = "addOccupantWithPresetTicks", at = @At("HEAD"), require = 0)
    private void judgementday$onBeeEnters(Entity bee, boolean hasNectar, int ticksInHive, CallbackInfo ci) {
        AnimalExtinctionHandler.EMPTY_NEW_NESTS.set(false);
    }
}
