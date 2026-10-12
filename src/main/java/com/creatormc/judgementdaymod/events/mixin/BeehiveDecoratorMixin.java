package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bee nests placed on new trees (e.g. saplings grown after the apocalypse) come
 * with brand new bees: once animals are extinct the nest is still placed, empty.
 * Bees that players keep in their hives are not affected.
 */
@Mixin(BeehiveDecorator.class)
public class BeehiveDecoratorMixin {

    @Unique
    private static final ThreadLocal<Boolean> judgementday$noBees = ThreadLocal.withInitial(() -> false);

    @Inject(method = "place", at = @At("HEAD"))
    private void judgementday$onPlace(TreeDecorator.Context context, CallbackInfo ci) {
        boolean extinct = context.level() instanceof WorldGenLevel level
                && AnimalExtinctionHandler.isExtinctIn(level.getLevel());
        judgementday$noBees.set(extinct);
    }

    @Redirect(method = "lambda$place$6", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity;storeBee(Lnet/minecraft/nbt/CompoundTag;IZ)V"))
    private static void judgementday$storeBee(BeehiveBlockEntity hive, CompoundTag bee, int ticksInHive, boolean hasNectar) {
        if (!judgementday$noBees.get()) {
            hive.storeBee(bee, ticksInHive, hasNectar);
        }
    }
}
