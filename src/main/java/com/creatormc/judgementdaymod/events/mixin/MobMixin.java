package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge turns the vanilla calls to Mob#finalizeSpawn into its spawn event, but not
 * the calls other mods make themselves: check those too, so that their animals do
 * not appear on their own after the apocalypse either (see AnimalExtinctionHandler).
 * The mob is only flagged, Forge then refuses it when it tries to join the level.
 */
@Mixin(Mob.class)
public class MobMixin {

    @Inject(method = "finalizeSpawn", at = @At("HEAD"), require = 0)
    private void judgementday$onFinalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
            MobSpawnType spawnType, SpawnGroupData spawnData, CompoundTag tag,
            CallbackInfoReturnable<SpawnGroupData> cir) {
        Mob self = (Mob) (Object) this;
        if (!self.isAddedToWorld() && AnimalExtinctionHandler.blocksSpawn(level, spawnType, self)) {
            self.setSpawnCancelled(true);
        }
    }
}
