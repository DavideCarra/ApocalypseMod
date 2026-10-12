package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.events.mixin.ChunkMapAccessor;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypseChunkData;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Mobs only burn while a player is near them (see ApocalypseDamageHandler), so the
 * ones left in the open in an area nobody visited during the apocalypse would still
 * be there when a player comes back, even after the apocalypse is over.
 *
 * The day each overworld chunk was last loaded is saved. When a chunk loads again
 * after at least one full apocalypse day without anybody around, the mobs it brings
 * back that were exposed (sky above them, above the damage height, not in water and
 * not immune to fire) are not loaded back: they burned while nobody was looking.
 * Mobs sheltered underground, under a roof or in water survive.
 */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class UnattendedChunkHandler {

    // Entities are loaded a few ticks after their chunk: how long to wait for them
    private static final long ENTITY_LOAD_WINDOW_TICKS = 1200;

    // Chunks loaded back after being left alone → game time until which their loading mobs are checked
    private static final Long2LongOpenHashMap sweepUntil = new Long2LongOpenHashMap();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD)
            return;
        if (!(event.getChunk() instanceof LevelChunk chunk) || !level.getServer().isSameThread())
            return;

        final long key = chunk.getPos().toLong();
        final int today = ConfigManager.apocalypseCurrentDay;
        ApocalypseChunkData data = ApocalypseChunkData.get(level);

        final int lastLoadedDay = data.getLastLoadedDay(key);
        data.setLastLoadedDay(key, today);

        final boolean unattended;
        if (lastLoadedDay != ConfigManager.UNSET) {
            unattended = unattendedApocalypseDays(lastLoadedDay, today) > 0;
        } else {
            // Chunk last loaded by an older version (no day saved): it was left alone if
            // it missed a destruction step the rest of the world went through
            unattended = !event.isNewChunk() && Generator.missedSteps(data.getPhase(chunk.getPos()));
        }

        if (unattended) {
            sweepUntil.put(key, level.getGameTime() + ENTITY_LOAD_WINDOW_TICKS);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD)
            return;
        if (!(event.getChunk() instanceof LevelChunk chunk))
            return;

        final long key = chunk.getPos().toLong();
        ApocalypseChunkData.get(level).setLastLoadedDay(key, ConfigManager.apocalypseCurrentDay);
        sweepUntil.remove(key);
    }

    /**
     * The chunks still loaded when the server stops are saved without an unload
     * event: record today for them (before the world data is written).
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel level = event.getServer().getLevel(Level.OVERWORLD);
        if (level != null) {
            ApocalypseChunkData data = ApocalypseChunkData.get(level);
            final int today = ConfigManager.apocalypseCurrentDay;
            for (ChunkHolder holder : ((ChunkMapAccessor) level.getChunkSource().chunkMap).getChunks_JD()) {
                ChunkPos pos = holder.getPos();
                if (level.hasChunk(pos.x, pos.z)) {
                    data.setLastLoadedDay(pos.toLong(), today);
                }
            }
        }
        sweepUntil.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        sweepUntil.clear();
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || sweepUntil.isEmpty())
            return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD)
            return;
        if (!(event.getEntity() instanceof Mob mob))
            return;

        final long key = ChunkPos.asLong(SectionPos.blockToSectionCoord(mob.getBlockX()),
                SectionPos.blockToSectionCoord(mob.getBlockZ()));
        final long until = sweepUntil.get(key);
        if (until == 0L || level.getGameTime() > until)
            return;

        // Riders and mounts are left alone: removing only one of them would break the pair
        if (mob.isPassenger() || mob.isVehicle())
            return;

        if (burnedInTheOpen(level, mob)) {
            event.setCanceled(true);
        }
    }

    private static boolean burnedInTheOpen(ServerLevel level, Entity entity) {
        if (entity.fireImmune())
            return false;
        if (entity instanceof Mob mob && mob.hasEffect(MobEffects.FIRE_RESISTANCE))
            return false;
        if (entity.getY() < ConfigManager.minDamageHeight)
            return false;

        BlockPos pos = entity.blockPosition();
        if (level.getFluidState(pos).is(FluidTags.WATER))
            return false;
        return level.canSeeSky(pos);
    }

    /**
     * Number of full days strictly between the two days during which the apocalypse
     * was burning the surface (day 0 up to the day before the end).
     */
    private static int unattendedApocalypseDays(int lastLoadedDay, int today) {
        final int from = Math.max(lastLoadedDay + 1, 0);
        final int to = Math.min(today - 1, ConfigManager.apocalypseEndDay - 1);
        return Math.max(0, to - from + 1);
    }
}
