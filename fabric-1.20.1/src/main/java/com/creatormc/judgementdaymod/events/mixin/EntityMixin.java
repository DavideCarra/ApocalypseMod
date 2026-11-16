package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.PersistentDataHolder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What Forge adds to every entity and the mod uses: its persistent data, saved with it
 * under "ForgeData" exactly as Forge saves it (worlds keep it between the two loaders),
 * and whether it is in a level (Forge's isAddedToWorld: in a level's entity manager).
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements PersistentDataHolder {

    @Shadow
    private EntityInLevelCallback levelCallback;

    @Unique
    private CompoundTag judgementday$persistentData;

    @Override
    public CompoundTag judgementday$getPersistentData() {
        if (judgementday$persistentData == null)
            judgementday$persistentData = new CompoundTag();
        return judgementday$persistentData;
    }

    /** True if the entity is in a level (added to its entity manager, not removed since). */
    @Override
    public boolean judgementday$isAddedToWorld() {
        return levelCallback != EntityInLevelCallback.NULL;
    }

    @Inject(method = "saveWithoutId", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"))
    private void judgementday$savePersistentData(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if (judgementday$persistentData != null)
            tag.put("ForgeData", judgementday$persistentData.copy());
    }

    @Inject(method = "load", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"))
    private void judgementday$loadPersistentData(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("ForgeData", Tag.TAG_COMPOUND))
            judgementday$persistentData = tag.getCompound("ForgeData");
    }
}
