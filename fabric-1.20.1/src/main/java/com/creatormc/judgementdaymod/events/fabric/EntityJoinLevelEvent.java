package com.creatormc.judgementdaymod.events.fabric;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * An entity joins a level, fired where Forge fires its EntityJoinLevelEvent: when it is
 * added to the level's entity manager, as a new entity or loaded with its chunk
 * (loadedFromDisk). A cancelled event keeps the entity out of the level.
 */
public final class EntityJoinLevelEvent {
    private final Entity entity;
    private final Level level;
    private final boolean loadedFromDisk;
    private boolean canceled;

    public EntityJoinLevelEvent(Entity entity, Level level, boolean loadedFromDisk) {
        this.entity = entity;
        this.level = level;
        this.loadedFromDisk = loadedFromDisk;
    }

    public Entity getEntity() {
        return entity;
    }

    public Level getLevel() {
        return level;
    }

    public boolean loadedFromDisk() {
        return loadedFromDisk;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
