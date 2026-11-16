package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.ModEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player starts dying (ServerPlayer#die does not call LivingEntity#die), as Forge's LivingDeathEvent. */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void judgementday$onDie(DamageSource source, CallbackInfo ci) {
        ModEvents.livingDeath((ServerPlayer) (Object) this);
    }
}
