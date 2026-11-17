package com.creatormc.judgementdaymod.events.fabric;

import net.minecraft.nbt.CompoundTag;

/**
 * What Forge adds to every entity: data of mods saved with it, under "ForgeData" as Forge
 * does, so worlds keep it when moved between the two loaders. Implemented on Entity by
 * EntityMixin.
 */
public interface PersistentDataHolder {
    CompoundTag judgementday$getPersistentData();

    /** Forge's isAddedToWorld: the entity is in a level's entity manager. */
    boolean judgementday$isAddedToWorld();

    static boolean isAddedToWorld(net.minecraft.world.entity.Entity entity) {
        return ((PersistentDataHolder) entity).judgementday$isAddedToWorld();
    }
}
