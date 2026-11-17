package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.ModEvents;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A living entity starts dying, where Forge fires its LivingDeathEvent (start of die). */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void judgementday$onDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.level().isClientSide())
            ModEvents.livingDeath(self);
    }
}
