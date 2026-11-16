package com.creatormc.judgementdaymod.events.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler;
import com.creatormc.judgementdaymod.handlers.ApocalypseDamageHandler;
import com.creatormc.judgementdaymod.handlers.DayTracker;
import com.creatormc.judgementdaymod.handlers.RitualEvents;
import com.creatormc.judgementdaymod.handlers.UnattendedChunkHandler;
import com.creatormc.judgementdaymod.handlers.WaterEvaporateHandler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The game events the mod listens to that Fabric API has no event for, fired by the mod's
 * mixins at the places Forge fires its own. Each one runs the mod's listeners in the order
 * Forge ran them (by priority; a cancelled event skips the listeners that do not receive
 * cancelled events), then the extra listeners (the self test harness, as Forge's lowest
 * priority listeners receiving cancelled events). Server thread.
 */
public final class ModEvents {

    public static final List<Consumer<EntityJoinLevelEvent>> JOIN_LISTENERS = new ArrayList<>();

    private ModEvents() {
    }

    /**
     * An entity is added to a server level's entity manager.
     *
     * @return false if it must be kept out of the level
     */
    public static boolean entityJoin(Entity entity, boolean loadedFromDisk) {
        final EntityJoinLevelEvent event = new EntityJoinLevelEvent(entity, entity.level(), loadedFromDisk);
        // Forge's own listener first (highest priority): a refused spawn keeps the mob out
        if (entity instanceof Mob mob && ((SpawnCancellable) mob).judgementday$isSpawnCancelled())
            event.setCanceled(true);
        if (!event.isCanceled())
            UnattendedChunkHandler.onEntityJoin(event);
        AnimalExtinctionHandler.onEntityJoin(event);
        for (int i = 0; i < JOIN_LISTENERS.size(); i++)
            JOIN_LISTENERS.get(i).accept(event);
        return !event.isCanceled();
    }

    /** An entity stops being tracked by a server level. */
    public static void entityLeave(Entity entity, ServerLevel level) {
        final EntityLeaveLevelEvent event = new EntityLeaveLevelEvent(entity, level);
        UnattendedChunkHandler.onEntityLeave(event);
        ApocalypseDamageHandler.onEntityLeave(event);
    }

    /** A chunk was sent to a player. */
    public static void chunkWatch(ServerPlayer player, LevelChunk chunk, ServerLevel level) {
        DayTracker.onChunkWatch(new ChunkWatchEvent(player, chunk, level));
    }

    /** A living entity starts dying (server). */
    public static void livingDeath(LivingEntity entity) {
        ApocalypseDamageHandler.onEntityDeath(entity);
    }

    /**
     * A player uses a block (server).
     *
     * @return the result the use ends with, or null if it goes on
     */
    public static InteractionResult rightClickBlock(Player player, InteractionHand hand, BlockHitResult hit) {
        final RightClickBlockEvent event = new RightClickBlockEvent(player, hand, hit);
        RitualEvents.onRightClickBlock(event);
        if (!event.isCanceled())
            WaterEvaporateHandler.onPlayerUseBucket(event);
        return event.isCanceled() ? event.getCancellationResult() : null;
    }
}
