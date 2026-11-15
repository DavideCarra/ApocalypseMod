package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.UnattendedChunkHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A world played with the Forge version keeps the last day a mob was seen in the mob's
 * Forge persistent data ("ForgeData"), which NeoForge does not read: it is carried over
 * into NeoForge's when the mob is loaded (see UnattendedChunkHandler). Only mobs that have
 * it are touched.
 */
@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "load", at = @At("TAIL"), require = 0)
    private void judgementday$keepLastSeenDay(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("ForgeData", Tag.TAG_COMPOUND)) {
            UnattendedChunkHandler.carryOverForgeData((Entity) (Object) this, tag.getCompound("ForgeData"));
        }
    }
}
