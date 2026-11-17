package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.PersistentDataHolder;
import com.creatormc.judgementdaymod.events.fabric.SpawnCancellable;
import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every spawn path that makes a mob appear (structures, spawners, jockeys, reinforcements,
 * other mods...) calls Mob#finalizeSpawn: an animal that may not appear any more is flagged
 * there, as Forge's spawn event flags it (see AnimalExtinctionHandler), and the flag keeps
 * it out of the level when it tries to join it (see ModEvents). The flag is the one Forge
 * adds to every mob.
 */
@Mixin(Mob.class)
public class MobMixin implements SpawnCancellable {

    @Unique
    private boolean judgementday$spawnCancelled;

    @Override
    public boolean judgementday$isSpawnCancelled() {
        return judgementday$spawnCancelled;
    }

    @Override
    public void judgementday$setSpawnCancelled(boolean cancel) {
        judgementday$spawnCancelled = cancel;
    }

    @Inject(method = "finalizeSpawn", at = @At("HEAD"), require = 0)
    private void judgementday$onFinalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
            MobSpawnType spawnType, SpawnGroupData spawnData, CompoundTag tag,
            CallbackInfoReturnable<SpawnGroupData> cir) {
        Mob self = (Mob) (Object) this;
        if (!PersistentDataHolder.isAddedToWorld(self) && AnimalExtinctionHandler.blocksSpawn(level, spawnType, self)) {
            judgementday$spawnCancelled = true;
        }
    }
}
