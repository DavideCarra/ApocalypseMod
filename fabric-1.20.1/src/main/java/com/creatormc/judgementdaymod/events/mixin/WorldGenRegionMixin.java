package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.SpawnCancellable;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A mob whose spawn was refused is not added to a chunk being generated, as Forge does. */
@Mixin(WorldGenRegion.class)
public class WorldGenRegionMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void judgementday$onAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Mob mob && ((SpawnCancellable) mob).judgementday$isSpawnCancelled())
            cir.setReturnValue(false);
    }
}
