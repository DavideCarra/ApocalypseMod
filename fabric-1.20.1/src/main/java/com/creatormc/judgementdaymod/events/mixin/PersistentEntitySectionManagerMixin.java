package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.ModEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An entity joins a server level: new (addNewEntity) or loaded with its chunk (the legacy
 * and world generation entities), where Forge fires its EntityJoinLevelEvent. A refused
 * entity is not added.
 */
@Mixin(PersistentEntitySectionManager.class)
public class PersistentEntitySectionManagerMixin {

    @Inject(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At("HEAD"), cancellable = true)
    private void judgementday$onAddEntity(EntityAccess access, boolean loadedFromDisk, CallbackInfoReturnable<Boolean> cir) {
        if (access instanceof Entity entity && entity.level() instanceof ServerLevel
                && !ModEvents.entityJoin(entity, loadedFromDisk)) {
            cir.setReturnValue(false);
        }
    }
}
