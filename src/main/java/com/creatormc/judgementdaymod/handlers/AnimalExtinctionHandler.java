package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ConfigManager;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Once the apocalypse has burned the world (100% reached, also after it ends or
 * after a ritual done past 100%) no animal appears on its own any more in the
 * overworld: the only animals left are the ones players kept alive underground,
 * and their young.
 *
 * Works for animals added by other mods too: they are recognised by their spawn
 * category (or class) and stopped through Forge's spawn events, which natural
 * spawning, world generation, structures, spawners and the other vanilla spawn
 * paths go through (as do mods that spawn through EntityType or Forge's event
 * factory, see also MobMixin). Breeding, eggs, buckets, spawn eggs, dispensers,
 * commands and conversions are left alone; any other spawn of an animal is refused,
 * summons made by other mods included. New bee nests come without bees (see
 * BeehiveDecoratorMixin).
 */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AnimalExtinctionHandler {

    // True while a bee nest decorator fills a new nest that must stay empty (per thread:
    // trees grow on the server thread and on world generation threads)
    public static final ThreadLocal<Boolean> EMPTY_NEW_NESTS = ThreadLocal.withInitial(() -> false);

    /**
     * Natural spawning and world generation: refused before the mob is even created.
     * Called from world generation threads too.
     */
    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (isBlockedSpawn(event.getLevel(), event.getSpawnType()) && isAnimalCategory(event.getEntityType())) {
            event.setResult(Event.Result.DENY);
        }
    }

    /**
     * Every other spawn path (spawners, structures, jockeys, wandering trader llamas,
     * skeleton traps...): the spawn is cancelled and the mob never joins the world.
     * Also when another mod cancelled the event (that only skips the mob's setup).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        // A mob already in the world can no longer be refused (Forge would throw)
        if (!event.getEntity().isAddedToWorld()
                && blocksSpawn(event.getLevel(), event.getSpawnType(), event.getEntity())) {
            event.setSpawnCancelled(true);
        }
    }

    /**
     * Natural spawning of the animal categories is not even attempted (it would only
     * be refused, every tick, since their mob caps never fill). See NaturalSpawnerMixin.
     */
    public static boolean skipsNaturalSpawning(Level level, MobCategory category) {
        return isExtinct() && level.dimension() == Level.OVERWORLD && isAnimalCategory(category);
    }

    /**
     * True if this mob, being spawned for this reason, is an animal that may not appear.
     * Also used by MobMixin for mods that call Mob#finalizeSpawn themselves.
     */
    public static boolean blocksSpawn(ServerLevelAccessor level, MobSpawnType spawnType, Mob mob) {
        return isBlockedSpawn(level, spawnType) && isAnimal(mob);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || event.loadedFromDisk())
            return;

        // The skeleton trap horse that lightning creates does not go through any spawn event
        if (!event.isCanceled() && mob instanceof SkeletonHorse horse && horse.isTrap()
                && isExtinctIn(event.getLevel())) {
            event.setCanceled(true);
        }

        // An animal refused here: a mount (chicken jockey, skeleton trap horse) lets its
        // riders go, a rider (baby strider jockey) leaves its mount, or they would stay
        // attached to a mob that is not in the world
        if (event.isCanceled() && mob.isSpawnCancelled() && isExtinctIn(event.getLevel()) && isAnimal(mob)) {
            if (mob.isVehicle())
                mob.ejectPassengers();
            if (mob.isPassenger())
                mob.stopRiding();
        }
    }

    private static boolean isBlockedSpawn(ServerLevelAccessor level, MobSpawnType spawnType) {
        if (!isExtinct() || spawnType == null || level == null || level.getLevel() == null
                || level.getLevel().dimension() != Level.OVERWORLD) {
            return false;
        }

        switch (spawnType) {
            case BREEDING:
            case SPAWN_EGG:
            case DISPENSER:
            case BUCKET:
            case COMMAND:
            case CONVERSION:
                return false;
            default:
                return true;
        }
    }

    /**
     * From the day the apocalypse reaches 100% on, also once it is over. A ritual done
     * before 100% leaves the animals alone (apocalypseActive is set the day 100% is
     * reached and never cleared).
     */
    public static boolean isExtinct() {
        return ConfigManager.apocalypseActive
                || (ConfigManager.apocalypseCurrentDay < ConfigManager.apocalypseEndDay
                        && ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseMaxDays);
    }

    /**
     * True if no animal may appear in this level (overworld, from 100% on).
     * Accepts a server level or a world generation region.
     */
    public static boolean isExtinctIn(LevelReader level) {
        if (!isExtinct())
            return false;
        if (level instanceof Level l)
            return l.dimension() == Level.OVERWORLD;
        if (level instanceof WorldGenLevel region)
            return region.getLevel().dimension() == Level.OVERWORLD;
        return false;
    }

    private static boolean isAnimalCategory(EntityType<?> type) {
        return isAnimalCategory(type.getCategory());
    }

    private static boolean isAnimalCategory(MobCategory category) {
        switch (category) {
            case CREATURE:
            case AMBIENT:
            case AXOLOTLS:
            case UNDERGROUND_WATER_CREATURE:
            case WATER_CREATURE:
            case WATER_AMBIENT:
                return true;
            default:
                return false;
        }
    }

    private static boolean isAnimal(Mob mob) {
        // Traders, villagers and other characters are not animals, whatever their category
        if (mob instanceof Npc || mob instanceof Merchant || mob instanceof Allay)
            return false;
        return isAnimalCategory(mob.getType())
                || mob instanceof Animal
                || mob instanceof WaterAnimal
                || mob instanceof AmbientCreature;
    }
}
