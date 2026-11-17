package com.creatormc.judgementdaymod.events.fabric;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** An entity leaves a level (end of its tracking), where Forge fires its EntityLeaveLevelEvent. */
public record EntityLeaveLevelEvent(Entity getEntity, Level getLevel) {
}
