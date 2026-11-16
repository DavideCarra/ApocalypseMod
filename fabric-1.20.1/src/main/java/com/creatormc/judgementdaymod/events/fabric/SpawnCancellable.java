package com.creatormc.judgementdaymod.events.fabric;

/**
 * What Forge adds to every mob: a spawn can be refused before the mob joins the level
 * (see MobMixin, EntityEvents). Implemented on Mob by MobMixin.
 */
public interface SpawnCancellable {
    boolean judgementday$isSpawnCancelled();

    void judgementday$setSpawnCancelled(boolean cancel);
}
