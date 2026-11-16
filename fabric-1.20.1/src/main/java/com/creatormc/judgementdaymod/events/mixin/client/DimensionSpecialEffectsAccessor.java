package com.creatormc.judgementdaymod.events.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The effects of each dimension type, which the apocalypse sky replaces for the overworld. */
@Mixin(DimensionSpecialEffects.class)
public interface DimensionSpecialEffectsAccessor {
    @Accessor("EFFECTS")
    static Object2ObjectMap<ResourceLocation, DimensionSpecialEffects> getEffects() {
        throw new AssertionError();
    }
}
