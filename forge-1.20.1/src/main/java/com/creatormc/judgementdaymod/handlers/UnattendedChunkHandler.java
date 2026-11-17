package com.creatormc.judgementdaymod.handlers;

import java.util.ArrayList;
import java.util.List;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypseChunkData;
import com.creatormc.judgementdaymod.utilities.ChunkLightSync;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Mobs only burn while a player is near them (see ApocalypseDamageHandler), so the
 * ones left in the open in an area nobody visited during the apocalypse would still
 * be there when a player comes back, even after the apocalypse is over.
 *
 * Every overworld mob remembers the last day it was in the loaded world (written
 * when it is saved with its chunk, when the server stops and on every new day). When
 * it is loaded back after at least one full apocalypse day, it is not loaded back if
 * it was exposed (under the open sky, at any depth, not in water, not immune to fire,
 * not protected by another mod): it burned while nobody was looking. Mobs sheltered
 * underground, under a roof or in water survive.
 *
 * A mob usually comes back before its chunk catches up on the steps it missed: one
 * that survives then (e.g. under water that is about to evaporate, or under trees
 * about to burn) is judged again once the chunk has caught up and its light is computed.
 */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class UnattendedChunkHandler {

    // Last day the mob was in the loaded world (in its Forge persistent data)
    private static final String LAST_SEEN_DAY = "judgementday_lastSeenDay";

    // Unattended mobs that survived their loading in a chunk still catching up, by chunk.
    // Main server thread only
    private static final Long2ObjectOpenHashMap<List<Mob>> waiting = new Long2ObjectOpenHashMap<>();

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

        stamp(mob, dayToSave());
    }

    /**
     * Today, but never past the end: after it nothing burns any more.
     */
    private static int dayToSave() {
        return Math.min(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseEndDay);
    }

    private static void stamp(Mob mob, int day) {
        mob.getPersistentData().putInt(LAST_SEEN_DAY, day);
    }

    /**
     * The mobs still loaded when the server stops are saved without leaving the level.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stampLoadedMobs(event.getServer().getLevel(Level.OVERWORLD));
        waiting.clear();
    }

    /**
     * A new day started: the loaded mobs are seen today (they are saved again only
     * when their chunk is, so a crash would otherwise leave an older day on disk).
     */
    public static void onNewDay(ServerLevel overworld) {
        stampLoadedMobs(overworld);
    }

    private static void stampLoadedMobs(ServerLevel level) {
        if (level == null)
            return;
        final int day = dayToSave();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob) {
                stamp(mob, day);
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

        final CompoundTag data = mob.getPersistentData();
        final boolean unattended;
        if (data.contains(LAST_SEEN_DAY, Tag.TAG_INT)) {
            unattended = unattendedApocalypseDays(data.getInt(LAST_SEEN_DAY), ConfigManager.apocalypseCurrentDay) > 0;
        } else {
            // Saved by an older version (no day recorded): the chunk was left alone if it
            // missed one of the phases (the only steps older versions recorded)
            ChunkPos pos = new ChunkPos(SectionPos.blockToSectionCoord(mob.getBlockX()),
                    SectionPos.blockToSectionCoord(mob.getBlockZ()));
            final int savedStep = ApocalypseChunkData.get(level).getPhase(pos);
            unattended = savedStep >= 0 && savedStep != Generator.PHASE_DONE
                    && savedStep < Math.min(5, Generator.currentStep());
        }

        if (!unattended)
            return;
        if (burnedInTheOpen(level, mob)) {
            event.setCanceled(true);
            ApocalypseDamageHandler.forget(mob.getUUID());
            return;
        }
        final long chunk = ChunkPos.asLong(SectionPos.blockToSectionCoord(mob.getBlockX()),
                SectionPos.blockToSectionCoord(mob.getBlockZ()));
        if (!caughtUp(level, chunk) || ChunkLightSync.lightPending(level, chunk)) {
            waiting.computeIfAbsent(chunk, k -> new ArrayList<>()).add(mob);
        }
    }

    /**
     * The chunk has just caught up on the steps it missed.
     */
    public static void chunkCaughtUp(ServerLevel level, long chunk) {
        if (!waiting.isEmpty()) {
            judgeAgain(level, chunk);
        }
    }

    /**
     * The light checks queued around the chunk are computed.
     */
    public static void lightDone(ServerLevel level, long chunk) {
        if (!waiting.isEmpty()) {
            judgeAgain(level, chunk);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        // Its mobs leave with it, stamped with today: judged again when they come back
        if (!waiting.isEmpty() && event.getLevel() instanceof ServerLevel) {
            waiting.remove(event.getChunk().getPos().toLong());
        }
    }

    /**
     * The mobs that survived their loading in the chunk are judged again once it has
     * caught up and its light is computed: a burned one is removed, as if it had not
     * been loaded back.
     */
    private static void judgeAgain(ServerLevel level, long chunk) {
        final List<Mob> mobs = waiting.get(chunk);
        if (mobs == null || !caughtUp(level, chunk) || ChunkLightSync.lightPending(level, chunk))
            return;
        waiting.remove(chunk);
        for (Mob mob : mobs) {
            if (mob.isRemoved() || mob.level() != level || mob.isPassenger() || mob.isVehicle())
                continue;
            if (burnedInTheOpen(level, mob)) {
                ApocalypseDamageHandler.forget(mob.getUUID());
                mob.discard();
            }
        }
    }

    /**
     * The chunk has had every step the world has reached (it is not processed again).
     */
    private static boolean caughtUp(ServerLevel level, long chunk) {
        final int savedStep = ApocalypseChunkData.get(level).getPhase(new ChunkPos(chunk));
        return savedStep == Generator.PHASE_DONE || savedStep >= Generator.currentStep();
    }

    private static boolean burnedInTheOpen(ServerLevel level, Mob mob) {
        // Fire immune or invulnerable mobs never take the apocalypse fire damage
        final DamageSource fire = level.damageSources().inFire();
        if (mob.isInvulnerableTo(fire) || mob.hasEffect(MobEffects.FIRE_RESISTANCE))
            return false;

        // Read from the chunk in memory: never load or wait for one here
        BlockPos pos = mob.blockPosition();
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk == null || chunk.getFluidState(pos).is(FluidTags.WATER))
            return false;
        if (!level.canSeeSky(pos))
            return false;

        // The live burn goes through the damage events: mods protecting the mob from it
        // (claims, pets, NPCs) protect it here too
        try {
            return ForgeHooks.onLivingAttack(mob, fire, 2.0F);
        } catch (RuntimeException e) {
            return false;
        }
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
