package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.ModEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An entity stops being tracked by a server level, where Forge fires its EntityLeaveLevelEvent. */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public class ServerLevelEntityCallbacksMixin {

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void judgementday$onTrackingEnd(Entity entity, CallbackInfo ci) {
        if (entity.level() instanceof ServerLevel level)
            ModEvents.entityLeave(entity, level);
    }
}
