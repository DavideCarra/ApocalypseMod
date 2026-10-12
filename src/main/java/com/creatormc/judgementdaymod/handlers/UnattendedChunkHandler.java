package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypseChunkData;
import com.creatormc.judgementdaymod.utilities.ApocalypseMobData;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Mobs only burn while a player is near them (see ApocalypseDamageHandler), so the
 * ones left in the open in an area nobody visited during the apocalypse would still
 * be there when a player comes back, even after the apocalypse is over.
 *
 * The day the mobs of each overworld chunk were last saved to disk (chunk left
 * behind, or server stopped) is recorded. When they are loaded back after at least
 * one full apocalypse day, the ones that were exposed (sky above them, above the
 * damage height, not in water and not immune to fire) are not loaded back: they
 * burned while nobody was looking. Mobs sheltered underground, under a roof or in
 * water survive.
 */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class UnattendedChunkHandler {

    /**
     * A mob left behind with its chunk: remember the day. When a chunk stops being
     * accessible its mobs leave the level before being saved, still without a removal
     * reason (it is set to UNLOADED_TO_CHUNK right after).
     */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob))
            return;
        Entity.RemovalReason reason = mob.getRemovalReason();
        if (reason != null && reason != Entity.RemovalReason.UNLOADED_TO_CHUNK)
            return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD)
            return;

        ApocalypseMobData.get(level).setSavedDay(chunkKey(mob), dayToSave());
    }

    /**
     * Today, but never past the end: after it nothing burns any more, so the saved
     * day (and the data file) no longer needs to change.
     */
    private static int dayToSave() {
        return Math.min(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseEndDay);
    }

    /**
     * The mobs still loaded when the server stops are saved without leaving the level:
     * record today for their chunks (before the world data is written).
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel level = event.getServer().getLevel(Level.OVERWORLD);
        if (level == null)
            return;

        ApocalypseMobData data = ApocalypseMobData.get(level);
        final int day = dayToSave();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob) {
                data.setSavedDay(chunkKey(entity), day);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !(event.getEntity() instanceof Mob mob))
            return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD)
            return;

        // Riders and mounts are left alone: removing only one of them would break the pair
        if (mob.isPassenger() || mob.isVehicle())
            return;

        final int savedDay = ApocalypseMobData.get(level).getSavedDay(chunkKey(mob));
        final boolean unattended;
        if (savedDay != ConfigManager.UNSET) {
            unattended = unattendedApocalypseDays(savedDay, ConfigManager.apocalypseCurrentDay) > 0;
        } else {
            // Saved by an older version (no day recorded): the chunk was left alone if it
            // missed one of the phases (the only steps older versions recorded)
            ChunkPos pos = new ChunkPos(SectionPos.blockToSectionCoord(mob.getBlockX()),
                    SectionPos.blockToSectionCoord(mob.getBlockZ()));
            final int savedStep = ApocalypseChunkData.get(level).getPhase(pos);
            unattended = savedStep >= 0 && savedStep != Generator.PHASE_DONE
                    && savedStep < Math.min(5, Generator.currentStep());
        }

        if (unattended && burnedInTheOpen(level, mob)) {
            event.setCanceled(true);
            ApocalypseDamageHandler.forget(mob.getUUID());
        }
    }

    private static long chunkKey(Entity entity) {
        return ChunkPos.asLong(SectionPos.blockToSectionCoord(entity.getBlockX()),
                SectionPos.blockToSectionCoord(entity.getBlockZ()));
    }

    private static boolean burnedInTheOpen(ServerLevel level, Mob mob) {
        // Fire immune or invulnerable mobs never take the apocalypse fire damage
        if (mob.isInvulnerableTo(level.damageSources().inFire()) || mob.hasEffect(MobEffects.FIRE_RESISTANCE))
            return false;
        if (mob.getY() < ConfigManager.minDamageHeight)
            return false;

        BlockPos pos = mob.blockPosition();
        if (level.getFluidState(pos).is(FluidTags.WATER))
            return false;
        return level.canSeeSky(pos);
    }

    /**
     * Number of full days strictly between the two days during which the apocalypse
     * was burning the surface (day 0 up to the day before the end).
     */
    private static int unattendedApocalypseDays(int savedDay, int today) {
        final int from = Math.max(savedDay + 1, 0);
        final int to = Math.min(today - 1, ConfigManager.apocalypseEndDay - 1);
        return Math.max(0, to - from + 1);
    }
}
