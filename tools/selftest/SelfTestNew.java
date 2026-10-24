package com.creatormc.judgementdaymod.selftest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypseChunkData;
import com.creatormc.judgementdaymod.utilities.ChunkLightSync;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Checks of the new behaviour only (needs the new mod code). */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SelfTestNew {

    static int stage = 0;
    static int waitUntil = 0;
    static int fails = 0;

    // mob sweep state
    static int[] MOB_CHUNKS = { 300, 300, 310, 300, 320, 300, 330, 300 };

    /** Chunks used by a round: a fresh set per round, far from each other. */
    static int[] roundChunks(int round) {
        int z = 300 + round * 20;
        return new int[] { 300, z, 310, z, 320, z, 330, z };
    }
    static final List<UUID[]> cows = new ArrayList<>();
    static int mobRound = 0;
    // round: {unloadDay, reloadDay, expectExposedRemoved}
    static final int[][] ROUNDS = { { 60, 63, 1 }, { 60, 60, 0 }, { 210, 230, 0 }, { 195, 230, 1 } };

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST, receiveCanceled = true)
    public static void onJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!(SelfTestCommon.enabled() && "logic".equals(SelfTestCommon.scenario())) || !(event.getEntity() instanceof Cow cow) || event.getLevel().isClientSide())
            return;
        if (cow.isBaby() || event.loadedFromDisk())
            SelfTestCommon.log("DEBUG join cow baby=" + cow.isBaby() + " fromDisk=" + event.loadedFromDisk() + " canceled="
                    + event.isCanceled() + " at " + cow.blockPosition().toShortString() + " day=" + ConfigManager.apocalypseCurrentDay
                    + " sky=" + ((ServerLevel) event.getLevel()).canSeeSky(cow.blockPosition()));
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onChunkLoad(net.minecraftforge.event.level.ChunkEvent.Load event) {
        if (!(SelfTestCommon.enabled() && "logic".equals(SelfTestCommon.scenario())) || !(event.getLevel() instanceof ServerLevel level))
            return;
        ChunkPos p = event.getChunk().getPos();
        for (int i = 0; i < MOB_CHUNKS.length; i += 2)
            if (p.x == MOB_CHUNKS[i] && p.z == MOB_CHUNKS[i + 1])
                SelfTestCommon.log("DEBUG chunk load " + p + " new=" + event.isNewChunk() + " day=" + ConfigManager.apocalypseCurrentDay
                        + " level=" + event.getChunk().getClass().getSimpleName());
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok)
            fails++;
        SelfTestCommon.log((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " :: " + detail));
    }

    static void defaults() {
        ConfigManager.apocalypseMaxDays = 50;
        ConfigManager.apocalypseEndDay = 200;
        ConfigManager.minDamageHeight = 60;
        ConfigManager.minWaterEvaporationHeight = 60;
        ConfigManager.apocalypseCurrentDay = 0;
        ConfigManager.lastAppliedPhase = ConfigManager.UNSET;
        ConfigManager.lastApocalypseStep = ConfigManager.UNSET;
        ConfigManager.apocalypseActive = false;
        Generator.resetState();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (SelfTestCommon.server == null || event.phase != TickEvent.Phase.END)
            return;
        if (!(SelfTestCommon.enabled() && "logic".equals(SelfTestCommon.scenario())))
            return;
        try {
            logicTick();
        } catch (Throwable t) {
            SelfTestCommon.log("EXCEPTION " + t);
            for (StackTraceElement el : t.getStackTrace())
                SelfTestCommon.log("   at " + el);
            fails++;
            SelfTestCommon.finish();
        }
    }

    static void logicTick() {
        int tick = SelfTestCommon.tick;
        ServerLevel level = SelfTestCommon.server.getLevel(Level.OVERWORLD);
        if (stage == 0 && tick >= 20) {
            SelfTestCommon.freezeWorld(level);
            // Load the chunks of every mob round now, long before cows are put in them
            for (int round = 0; round < ROUNDS.length; round++) {
                int[] ch = roundChunks(round);
                for (int i = 0; i < ch.length; i += 2) {
                    level.setChunkForced(ch[i], ch[i + 1], true);
                    level.getChunk(ch[i], ch[i + 1]);
                }
            }
            stepTable();
            restart(level);
            smallMaxDays(level);
            ritualAndEnd(level);
            lightSyncStress(level);
            legacy(level);
            ritualWater(level);
            animals(level);
            animalsMore(level);
            reviewFixes(level);
            stage = 1;
            waitUntil = tick + 60;
        } else if (stage == 1 && tick >= waitUntil) {
            breedingStart(level);
            stage = 5;
            waitUntil = tick + 20;
        } else if (stage == 5 && tick >= waitUntil) {
            breedingCheck(level);
            // mob sweep: set up the first round
            defaults();
            mobRound = 0;
            MOB_CHUNKS = roundChunks(mobRound);
            setupMobs(level);
            stage = 2;
            waitUntil = tick + 40;
        } else if (stage == 2 && tick >= waitUntil) {
            // unload the chunks on the unload day
            ConfigManager.apocalypseCurrentDay = ROUNDS[mobRound][0];
            for (int i = 0; i < MOB_CHUNKS.length; i += 2)
                level.setChunkForced(MOB_CHUNKS[i], MOB_CHUNKS[i + 1], false);
            stage = 3;
            waitUntil = tick + 600;
        } else if (stage == 3) {
            boolean unloaded = true;
            for (int i = 0; i < MOB_CHUNKS.length; i += 2)
                if (level.hasChunk(MOB_CHUNKS[i], MOB_CHUNKS[i + 1]))
                    unloaded = false;
            if (unloaded || tick >= waitUntil) {
                check("mobs round " + mobRound + " chunks unloaded", unloaded, "");
                int stillLoaded = 0;
                for (UUID[] ids : cows)
                    for (UUID id : ids)
                        if (level.getEntity(id) != null)
                            stillLoaded++;
                SelfTestCommon.log("DEBUG cows still in memory after unload: " + stillLoaded);
                ConfigManager.apocalypseCurrentDay = ROUNDS[mobRound][1];
                for (int i = 0; i < MOB_CHUNKS.length; i += 2) {
                    level.setChunkForced(MOB_CHUNKS[i], MOB_CHUNKS[i + 1], true);
                    level.getChunk(MOB_CHUNKS[i], MOB_CHUNKS[i + 1]);
                }
                stage = 4;
                waitUntil = tick + 100;
            }
        } else if (stage == 4 && tick >= waitUntil) {
            verifyMobs(level);
            mobRound++;
            if (mobRound < ROUNDS.length) {
                MOB_CHUNKS = roundChunks(mobRound);
                setupMobs(level);
                stage = 2;
                waitUntil = tick + 40;
            } else {
                SelfTestCommon.log("RESULT fails=" + fails);
                SelfTestCommon.finish();
                stage = 99;
            }
        }
    }

    // ------------------------------------------------------------------ steps

    static void stepTable() {
        defaults();
        int[][] expected = { { -10, 0 }, { 0, 0 }, { 9, 0 }, { 10, 1 }, { 19, 1 }, { 20, 2 }, { 30, 3 }, { 40, 4 },
                { 49, 4 }, { 50, 5 }, { 59, 5 }, { 60, 6 }, { 79, 6 }, { 80, 7 }, { 180, 12 }, { 199, 12 } };
        for (int[] e : expected) {
            ConfigManager.apocalypseCurrentDay = e[0];
            int got = Generator.currentStep();
            check("step day " + e[0], got == e[1], "got " + got + " expected " + e[1]);
        }
        ConfigManager.apocalypseCurrentDay = 250;
        check("step after natural end", Generator.currentStep() == 12, "got " + Generator.currentStep());
    }

    static void restart(ServerLevel level) {
        FakePlayer player = FakePlayerFactory.getMinecraft(level);

        // Legacy config on day 26 (phase 2 already applied before the update): a restart
        // followed by a day change must not lower the heights again
        defaults();
        ConfigManager.apocalypseCurrentDay = 26;
        ConfigManager.minDamageHeight = 30;
        ConfigManager.minWaterEvaporationHeight = 30;
        ConfigManager.lastAppliedPhase = ConfigManager.UNSET; // as loaded from an old config file
        Generator.resetState();
        check("legacy lastAppliedPhase derived", ConfigManager.lastAppliedPhase == 2,
                "got " + ConfigManager.lastAppliedPhase);
        ConfigManager.apocalypseCurrentDay = 27;
        Generator.handleDayEvent(player);
        check("restart keeps heights", ConfigManager.minDamageHeight == 30 && ConfigManager.minWaterEvaporationHeight == 30,
                "damage=" + ConfigManager.minDamageHeight + " water=" + ConfigManager.minWaterEvaporationHeight);

        // Second restart on the same phase
        Generator.resetState();
        ConfigManager.apocalypseCurrentDay = 28;
        Generator.handleDayEvent(player);
        check("second restart keeps heights", ConfigManager.minDamageHeight == 30, "damage=" + ConfigManager.minDamageHeight);

        // Next phase is applied once (30 - 3 * 6 = 12)
        ConfigManager.apocalypseCurrentDay = 30;
        Generator.handleDayEvent(player);
        check("next phase applied", ConfigManager.minDamageHeight == 12 && ConfigManager.lastAppliedPhase == 3,
                "damage=" + ConfigManager.minDamageHeight + " phase=" + ConfigManager.lastAppliedPhase);
        Generator.resetState();
        ConfigManager.apocalypseCurrentDay = 31;
        Generator.handleDayEvent(player);
        check("phase not applied twice after restart", ConfigManager.minDamageHeight == 12, "damage=" + ConfigManager.minDamageHeight);

        // Fresh world: day 0 -> the first day change enters phase 0 (title/music), heights unchanged
        defaults();
        ConfigManager.apocalypseCurrentDay = 1;
        Generator.handleDayEvent(player);
        check("fresh world enters phase 0 on day 1", ConfigManager.lastAppliedPhase == 0 && ConfigManager.minDamageHeight == 60,
                "phase=" + ConfigManager.lastAppliedPhase + " damage=" + ConfigManager.minDamageHeight);

        // Day moved back by hand: phases are applied again from there
        ConfigManager.lastAppliedPhase = 4;
        ConfigManager.apocalypseCurrentDay = 5;
        Generator.resetState();
        check("day moved back resets last phase", ConfigManager.lastAppliedPhase == 0, "phase=" + ConfigManager.lastAppliedPhase);
        defaults();
    }

    static void smallMaxDays(ServerLevel level) {
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        String error = "";
        try {
            for (int max = 0; max <= 4; max++) {
                defaults();
                ConfigManager.apocalypseMaxDays = max;
                ConfigManager.apocalypseEndDay = 20;
                Generator.resetState();
                for (int day = -2; day <= 25; day++) {
                    ConfigManager.apocalypseCurrentDay = day;
                    Generator.handleDayEvent(player);
                    Generator.currentStep();
                }
            }
        } catch (Throwable t) {
            error = t.toString();
        }
        check("maxDays 0..4 no exception", error.isEmpty(), error);
        defaults();
    }

    static void ritualAndEnd(ServerLevel level) {
        // Ritual on day 30 (step 3): the world stays at step 3
        defaults();
        ConfigManager.apocalypseCurrentDay = 30;
        ConfigManager.lastApocalypseStep = Generator.currentStep();
        ConfigManager.apocalypseEndDay = 30;
        ConfigManager.apocalypseMaxDays = 29;
        check("ritual freezes step", Generator.currentStep() == 3, "got " + Generator.currentStep());
        ConfigManager.apocalypseCurrentDay = 300;
        check("ritual step stays later", Generator.currentStep() == 3, "got " + Generator.currentStep());
        Generator.resetState();
        check("ritual step survives restart", Generator.currentStep() == 3, "got " + Generator.currentStep());

        // A ritual step left over from another world is dropped when a new apocalypse runs
        ConfigManager.apocalypseCurrentDay = 5;
        ConfigManager.apocalypseEndDay = 200;
        ConfigManager.apocalypseMaxDays = 50;
        Generator.resetState();
        check("old ritual step cleared", ConfigManager.lastApocalypseStep == ConfigManager.UNSET, "got " + ConfigManager.lastApocalypseStep);

        // Real chunk processed after a ritual: catches up to step 3 once, then stays
        int cx = 200, cz = 200;
        level.setChunkForced(cx, cz, true);
        level.getChunk(cx, cz);
        ApocalypseChunkData data = ApocalypseChunkData.get(level);
        data.setPhase(new ChunkPos(cx, cz), 1);
        ConfigManager.apocalypseCurrentDay = 30;
        ConfigManager.lastApocalypseStep = 3;
        ConfigManager.apocalypseEndDay = 30;
        ConfigManager.apocalypseMaxDays = 29;
        Generator.processChunk(new ChunkToProcess(level, cx, cz));
        check("ritual chunk done", data.getPhase(new ChunkPos(cx, cz)) == 999, "phase=" + data.getPhase(new ChunkPos(cx, cz)));
        defaults();
    }

    static void lightSyncStress(ServerLevel level) {
        String error = "";
        try {
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 700; i++)
                    ChunkLightSync.lightQueued(level, 5000 + i * 3, 5000 + round * 7);
                for (int t = 0; t < 700; t++)
                    ChunkLightSync.tick();
            }
        } catch (Throwable t) {
            error = t.toString();
        }
        // The markers of this test come back only after it: start the real game clean
        ChunkLightSync.clear();
        check("ChunkLightSync many entries settle/expire without exception", error.isEmpty(), error);
    }

    // ---------------------------------------------------------------- legacy / ritual

    static void legacy(ServerLevel level) {
        // World that ended with an older version (config without the new keys)
        defaults();
        ConfigManager.apocalypseCurrentDay = 230;
        ConfigManager.legacyConfig = true;
        ConfigManager.stepOriginDay = ConfigManager.UNSET;
        ConfigManager.lastAppliedPhase = ConfigManager.UNSET;
        Generator.resetState();
        check("legacy ended world stays at step 5", Generator.currentStep() == 5 && !Generator.missedSteps(5),
                "step=" + Generator.currentStep() + " origin=" + ConfigManager.stepOriginDay);
        check("legacy chunk behind still missed", Generator.missedSteps(3), "");

        // World in the middle of the post-countdown with an older version
        defaults();
        ConfigManager.apocalypseCurrentDay = 70;
        ConfigManager.legacyConfig = true;
        ConfigManager.stepOriginDay = ConfigManager.UNSET;
        Generator.resetState();
        int at70 = Generator.currentStep();
        ConfigManager.apocalypseCurrentDay = 79;
        int at79 = Generator.currentStep();
        ConfigManager.apocalypseCurrentDay = 80;
        int at80 = Generator.currentStep();
        check("legacy mid world: no retroactive steps", at70 == 5 && at79 == 5 && at80 == 6,
                "70->" + at70 + " 79->" + at79 + " 80->" + at80);
        // Restart later: origin kept, not a legacy config any more
        Generator.resetState();
        check("legacy origin kept after restart", Generator.currentStep() == 6 && ConfigManager.stepOriginDay == 70,
                "step=" + Generator.currentStep() + " origin=" + ConfigManager.stepOriginDay);
        // New countdown: origin cleared
        ConfigManager.apocalypseCurrentDay = 3;
        Generator.resetState();
        check("new countdown clears origin", ConfigManager.stepOriginDay == ConfigManager.UNSET, "");
        defaults();
    }

    /** Ritual at step 1 (before evaporation starts): a chunk caught up later keeps its water. */
    static void ritualWater(ServerLevel level) {
        defaults();
        int cx = 210, cz = 210;
        level.setChunkForced(cx, cz, true);
        level.getChunk(cx, cz);
        BlockPos pool = new BlockPos((cx << 4) + 8, 100, (cz << 4) + 8);
        level.setBlock(pool.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(pool, Blocks.WATER.defaultBlockState(), 2);
        ApocalypseChunkData.get(level).setPhase(new ChunkPos(cx, cz), 0);
        ConfigManager.apocalypseCurrentDay = 12;
        ConfigManager.lastApocalypseStep = 1;
        ConfigManager.apocalypseEndDay = 12;
        ConfigManager.apocalypseMaxDays = 11;
        Generator.processChunk(new ChunkToProcess(level, cx, cz));
        check("ritual at step 1 keeps water", level.getBlockState(pool).is(Blocks.WATER),
                "state=" + level.getBlockState(pool));
        defaults();
    }

    // ---------------------------------------------------------------- animals

    static UUID breedA, breedB;

    static BlockPos surface(ServerLevel level, int x, int z) {
        level.setChunkForced(x >> 4, z >> 4, true);
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    static void animals(ServerLevel level) {
        defaults();
        BlockPos grass = surface(level, 2000, 2000);
        level.setBlock(grass.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 0; dy <= 2; dy++)
                    level.setBlock(grass.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
        level.setDayTime(6000);

        ConfigManager.apocalypseCurrentDay = 10;
        boolean before = SpawnPlacements.checkSpawnRules(EntityType.COW, level, MobSpawnType.NATURAL, grass, level.random);
        ConfigManager.apocalypseCurrentDay = 50;
        boolean after = SpawnPlacements.checkSpawnRules(EntityType.COW, level, MobSpawnType.NATURAL, grass, level.random);
        boolean afterGen = SpawnPlacements.checkSpawnRules(EntityType.COW, level, MobSpawnType.CHUNK_GENERATION, grass, level.random);
        check("cow natural spawn allowed before 100%", before, "");
        check("cow natural spawn denied from 100%", !after && !afterGen, "natural=" + after + " chunkgen=" + afterGen);
        boolean squid = SpawnPlacements.checkSpawnRules(EntityType.SQUID, level, MobSpawnType.NATURAL, grass, level.random);
        boolean bat = SpawnPlacements.checkSpawnRules(EntityType.BAT, level, MobSpawnType.NATURAL, grass, level.random);
        check("squid/bat natural spawn denied from 100%", !squid && !bat, "squid=" + squid + " bat=" + bat);

        // Zombie placement unaffected (only the mod's rule changes)
        ConfigManager.apocalypseCurrentDay = 10;
        boolean zBefore = SpawnPlacements.checkSpawnRules(EntityType.ZOMBIE, level, MobSpawnType.NATURAL, grass, level.random);
        ConfigManager.apocalypseCurrentDay = 50;
        boolean zAfter = SpawnPlacements.checkSpawnRules(EntityType.ZOMBIE, level, MobSpawnType.NATURAL, grass, level.random);
        check("zombie placement unaffected", zBefore == zAfter, "before=" + zBefore + " after=" + zAfter);

        ConfigManager.apocalypseCurrentDay = 250; // after the end
        ConfigManager.apocalypseActive = true; // the game reached 100%
        Entity structure = EntityType.COW.spawn(level, grass, MobSpawnType.STRUCTURE);
        Entity spawner = EntityType.COW.spawn(level, grass, MobSpawnType.SPAWNER);
        Entity egg = EntityType.COW.spawn(level, grass, MobSpawnType.SPAWN_EGG);
        Entity command = EntityType.COW.spawn(level, grass, MobSpawnType.COMMAND);
        Entity zombie = EntityType.ZOMBIE.spawn(level, grass, MobSpawnType.SPAWNER);
        Entity villager = EntityType.VILLAGER.spawn(level, grass, MobSpawnType.STRUCTURE);
        Entity summoned = EntityType.COW.spawn(level, grass, MobSpawnType.MOB_SUMMONED);
        Entity triggered = EntityType.COW.spawn(level, grass, MobSpawnType.TRIGGERED);
        Entity natural = EntityType.COW.spawn(level, grass, MobSpawnType.NATURAL);
        check("structure cow blocked after end", structure == null || !structure.isAddedToWorld(), "");
        check("spawner cow blocked after end", spawner == null || !spawner.isAddedToWorld(), "");
        check("natural cow blocked after end", natural == null || !natural.isAddedToWorld(), "");
        check("spawn egg cow allowed", egg != null && egg.isAddedToWorld(), "");
        check("command cow allowed", command != null && command.isAddedToWorld(), "");
        check("summoned cow allowed (another mod's ritual)", summoned != null && summoned.isAddedToWorld(), "");
        check("triggered cow allowed", triggered != null && triggered.isAddedToWorld(), "");
        for (Entity e : new Entity[] { summoned, triggered })
            if (e != null && e.isAddedToWorld())
                e.discard();
        check("spawner zombie allowed", zombie != null && zombie.isAddedToWorld(), "");
        check("structure villager allowed", villager != null && villager.isAddedToWorld(), "");
        if (zombie != null)
            zombie.discard();
        if (villager != null)
            villager.discard();
        if (command != null)
            command.discard();

        if (egg != null)
            egg.discard();
        breedPos = grass;
    }

    static BlockPos breedPos;

    /** Fixes from the second review. */
    static void reviewFixes(ServerLevel level) {
        BlockPos grass = breedPos;
        // A ritual before 100% leaves the animals alone, one after 100% does not
        defaults();
        ConfigManager.apocalypseCurrentDay = 15;
        ConfigManager.lastApocalypseStep = Generator.currentStep();
        ConfigManager.apocalypseEndDay = 15;
        ConfigManager.apocalypseMaxDays = 14;
        boolean cowEarly = SpawnPlacements.checkSpawnRules(EntityType.COW, level, MobSpawnType.NATURAL, grass, level.random);
        ConfigManager.apocalypseCurrentDay = 40;
        boolean extinctLater = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.isExtinct();
        check("ritual before 100% keeps animals", cowEarly && !extinctLater,
                "cow=" + cowEarly + " extinctLater=" + extinctLater + " step=" + Generator.currentStep());
        defaults();
        ConfigManager.apocalypseCurrentDay = 60;
        ConfigManager.lastApocalypseStep = Generator.currentStep();
        ConfigManager.apocalypseEndDay = 60;
        ConfigManager.apocalypseMaxDays = 59;
        ConfigManager.apocalypseCurrentDay = 70;
        ConfigManager.apocalypseActive = true; // the game reached 100%
        check("ritual after 100% keeps extinction", com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.isExtinct(), "");
        defaults();
        ConfigManager.apocalypseCurrentDay = 49;
        boolean at49 = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.isExtinct();
        ConfigManager.apocalypseCurrentDay = 50;
        boolean at50 = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.isExtinct();
        ConfigManager.apocalypseCurrentDay = 250;
        ConfigManager.apocalypseActive = true; // the game reached 100%
        boolean afterEnd = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.isExtinct();
        check("extinct exactly from 100% and after the end", !at49 && at50 && afterEnd, at49 + " " + at50 + " " + afterEnd);

        // Natural spawning of the animal categories is skipped up front
        ServerLevel nether = SelfTestCommon.server.getLevel(Level.NETHER);
        boolean skipAmbient = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.skipsNaturalSpawning(level, net.minecraft.world.entity.MobCategory.AMBIENT);
        boolean skipWater = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.skipsNaturalSpawning(level, net.minecraft.world.entity.MobCategory.WATER_AMBIENT);
        boolean skipMonster = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.skipsNaturalSpawning(level, net.minecraft.world.entity.MobCategory.MONSTER);
        boolean skipNether = nether != null && com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.skipsNaturalSpawning(nether, net.minecraft.world.entity.MobCategory.CREATURE);
        ConfigManager.apocalypseCurrentDay = 10;
        ConfigManager.apocalypseActive = false;
        boolean skipBefore = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.skipsNaturalSpawning(level, net.minecraft.world.entity.MobCategory.AMBIENT);
        check("natural spawner skips only extinct animal categories", skipAmbient && skipWater && !skipMonster && !skipNether && !skipBefore,
                skipAmbient + " " + skipWater + " " + skipMonster + " " + skipNether + " " + skipBefore);

        // A mod calling finalizeSpawn without a spawn type
        ConfigManager.apocalypseCurrentDay = 250;
        ConfigManager.apocalypseActive = true; // the game reached 100%
        String error = "";
        try {
            net.minecraft.world.entity.animal.Cow cow = EntityType.COW.create(level);
            cow.moveTo(grass.getX() + 0.5, grass.getY(), grass.getZ() + 0.5, 0, 0);
            cow.finalizeSpawn(level, level.getCurrentDifficultyAt(grass), null, null, null);
        } catch (Throwable t) {
            error = t.toString();
        }
        check("finalizeSpawn without spawn type", error.isEmpty(), error);

        // Another mod cancelled the spawn event: the animal is still refused
        net.minecraft.world.entity.animal.Cow cow2 = EntityType.COW.create(level);
        cow2.moveTo(grass.getX() + 0.5, grass.getY(), grass.getZ() + 0.5, 0, 0);
        net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn ev = new net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn(
                cow2, level, cow2.getX(), cow2.getY(), cow2.getZ(), level.getCurrentDifficultyAt(grass), MobSpawnType.SPAWNER, null, null, null);
        ev.setCanceled(true);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(ev);
        check("cancelled spawn event still refuses the animal", cow2.isSpawnCancelled(), "");

        // Loaded mobs get today's day on a new day
        Entity cmd = EntityType.COW.spawn(level, grass, MobSpawnType.COMMAND);
        com.creatormc.judgementdaymod.handlers.UnattendedChunkHandler.onNewDay(level);
        int stamped = cmd == null ? -1 : cmd.getPersistentData().getInt("judgementday_lastSeenDay");
        check("new day stamps loaded mobs", stamped == 200, "stamp=" + stamped);
        if (cmd != null)
            cmd.discard();

        // A bee entering a hive is kept even if a decorator left the flag set
        BlockPos hivePos = grass.above(3);
        level.setBlock(hivePos, Blocks.BEEHIVE.defaultBlockState(), 2);
        com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.EMPTY_NEW_NESTS.set(true);
        int occupants = -1;
        if (level.getBlockEntity(hivePos) instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity hive) {
            net.minecraft.world.entity.animal.Bee bee = EntityType.BEE.create(level);
            bee.moveTo(hivePos.getX() + 0.5, hivePos.getY() + 1, hivePos.getZ() + 0.5, 0, 0);
            hive.addOccupant(bee, false);
            occupants = hive.getOccupantCount();
        }
        boolean flag = com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.EMPTY_NEW_NESTS.get();
        com.creatormc.judgementdaymod.handlers.AnimalExtinctionHandler.EMPTY_NEW_NESTS.set(false);
        level.setBlock(hivePos, Blocks.AIR.defaultBlockState(), 2);
        check("bee entering a hive clears a stuck nest flag", occupants == 1 && !flag, "occupants=" + occupants + " flag=" + flag);
        defaults();
    }

    static void animalsMore(ServerLevel level) {
        defaults();
        BlockPos pos = surface(level, 2100, 2100);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(pos.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlock(pos.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
            }
        ConfigManager.apocalypseCurrentDay = 250;
        ConfigManager.apocalypseActive = true; // the game reached 100%

        Entity trader = EntityType.WANDERING_TRADER.spawn(level, pos, MobSpawnType.EVENT);
        Entity llama = EntityType.TRADER_LLAMA.spawn(level, pos, MobSpawnType.EVENT);
        Entity allay = EntityType.ALLAY.spawn(level, pos, MobSpawnType.STRUCTURE);
        check("wandering trader allowed after end", trader != null && trader.isAddedToWorld(), "");
        check("trader llama blocked after end", llama == null || !llama.isAddedToWorld(), "");
        check("allay allowed after end", allay != null && allay.isAddedToWorld(), "");
        for (Entity e : new Entity[] { trader, llama, allay })
            if (e != null && e.isAddedToWorld())
                e.discard();

        // Chicken jockey: the chicken is refused, the zombie must be free and in the world
        net.minecraft.world.entity.animal.Chicken chicken = EntityType.CHICKEN.create(level);
        chicken.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(chicken, level, level.getCurrentDifficultyAt(pos),
                MobSpawnType.JOCKEY, null, null);
        net.minecraft.world.entity.monster.Zombie zombie = EntityType.ZOMBIE.create(level);
        zombie.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        zombie.startRiding(chicken, true);
        level.addFreshEntity(chicken);
        level.addFreshEntity(zombie);
        check("jockey chicken refused, zombie freed", !chicken.isAddedToWorld() && zombie.isAddedToWorld()
                && zombie.getVehicle() == null, "chicken=" + chicken.isAddedToWorld() + " zombie=" + zombie.isAddedToWorld()
                + " vehicle=" + zombie.getVehicle());
        zombie.discard();

        // Skeleton horses are undead: the lightning trap and the horses of its riders appear
        net.minecraft.world.entity.animal.horse.SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
        horse.setTrap(true);
        horse.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        level.addFreshEntity(horse);
        Entity riderHorse = EntityType.SKELETON_HORSE.spawn(level, pos, MobSpawnType.TRIGGERED);
        Entity structureHorse = EntityType.SKELETON_HORSE.spawn(level, pos, MobSpawnType.STRUCTURE);
        check("skeleton horses allowed after 100% (trap included)", horse.isAddedToWorld()
                && riderHorse != null && riderHorse.isAddedToWorld() && structureHorse != null && structureHorse.isAddedToWorld(),
                "trap=" + horse.isAddedToWorld() + " rider=" + (riderHorse != null && riderHorse.isAddedToWorld())
                        + " structure=" + (structureHorse != null && structureHorse.isAddedToWorld()));
        for (Entity e : new Entity[] { horse, riderHorse, structureHorse })
            if (e != null && e.isAddedToWorld())
                e.discard();

        // New bee nests: empty from 100% on, with bees before
        check("bee nest empty after the end", beeNest(level, pos.offset(0, 0, 0)) == 0, "");
        ConfigManager.apocalypseCurrentDay = 10;
        ConfigManager.apocalypseActive = false;
        int before = beeNest(level, pos.offset(0, 0, 3));
        check("bee nest has bees before 100%", before >= 2, "bees=" + before);
        defaults();
    }

    /** Places a bee nest decorator on a 5 block log column; returns the bees put in the nest. */
    static int beeNest(ServerLevel level, BlockPos base) {
        it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockPos> logs = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
        for (int y = 0; y < 5; y++) {
            BlockPos p = base.above(y);
            level.setBlock(p, Blocks.OAK_LOG.defaultBlockState(), 2);
            logs.add(p);
        }
        net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator.Context ctx =
                new net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator.Context(level,
                        (p, st) -> level.setBlock(p, st, 2), level.random, new java.util.HashSet<>(logs),
                        new java.util.HashSet<>(), new java.util.HashSet<>());
        new net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator(1.0f).place(ctx);
        int bees = -1;
        for (int y = -1; y < 7; y++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = base.offset(dx, y, dz);
                    if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity hive)
                        bees = hive.getOccupantCount();
                }
        return bees;
    }

    /** Breeding: two adult cows in love, in a chunk loaded for a while. */
    static void breedingStart(ServerLevel level) {
        ConfigManager.apocalypseCurrentDay = 250; // after the end
        ConfigManager.apocalypseActive = true; // the game reached 100%
        BlockPos grass = breedPos;
        Cow a = EntityType.COW.spawn(level, grass, MobSpawnType.SPAWN_EGG);
        Cow b = EntityType.COW.spawn(level, grass, MobSpawnType.SPAWN_EGG);
        if (a != null && b != null) {
            a.setInLove(null);
            b.setInLove(null);
            a.spawnChildFromBreeding(level, b);
            breedA = a.getUUID();
            breedB = b.getUUID();
            int babies = 0;
            for (Entity e : level.getAllEntities())
                if (e instanceof Cow cow && cow.isBaby())
                    babies++;
            SelfTestCommon.log("DEBUG breeding a=" + a.isAddedToWorld() + " b=" + b.isAddedToWorld() + " ageA=" + a.getAge()
                    + " babiesNow=" + babies);
        } else {
            SelfTestCommon.log("DEBUG breeding parents null a=" + a + " b=" + b);
        }
    }

    static void breedingCheck(ServerLevel level) {
        int babies = 0, cowsAll = 0;
        for (Entity e : level.getAllEntities())
            if (e instanceof Cow cow) {
                cowsAll++;
                if (cow.isBaby())
                    babies++;
            }
        SelfTestCommon.log("DEBUG breeding check cows=" + cowsAll + " parentA=" + (level.getEntity(breedA) != null)
                + " parentB=" + (level.getEntity(breedB) != null));
        check("breeding after the end gives a calf", babies >= 1, "babies=" + babies);
    }

    // ---------------------------------------------------------------- mob sweep

    /** Four cows per chunk: exposed, underground, in water, under a roof. */
    static void setupMobs(ServerLevel level) {
        cows.clear();
        for (int i = 0; i < MOB_CHUNKS.length; i += 2) {
            int cx = MOB_CHUNKS[i], cz = MOB_CHUNKS[i + 1];
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
            int bx = (cx << 4) + 8, bz = (cz << 4) + 8;
            int top = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz), 70);
            UUID[] ids = new UUID[4];
            // flat stone platform at top, open sky
            for (int dx = -7; dx <= 7; dx++)
                for (int dz = -7; dz <= 7; dz++) {
                    level.setBlock(new BlockPos(bx + dx, top - 1, bz + dz), Blocks.STONE.defaultBlockState(), 2);
                    for (int dy = 0; dy < 12; dy++)
                        level.setBlock(new BlockPos(bx + dx, top + dy, bz + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            ids[0] = spawnCow(level, new BlockPos(bx - 5, top, bz - 5));
            // underground room at y=20
            BlockPos room = new BlockPos(bx, 20, bz);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = -1; dy <= 3; dy++) {
                        boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -1 || dy == 3;
                        level.setBlock(room.offset(dx, dy, dz), wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                    }
            ids[1] = spawnCow(level, room);
            // water pool on the surface
            BlockPos pool = new BlockPos(bx + 5, top, bz - 5);
            level.setBlock(pool, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(pool.above(), Blocks.WATER.defaultBlockState(), 2);
            ids[2] = spawnCow(level, pool);
            // roof
            BlockPos roofed = new BlockPos(bx + 5, top, bz + 5);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlock(roofed.offset(dx, 3, dz), Blocks.STONE.defaultBlockState(), 2);
            ids[3] = spawnCow(level, roofed);
            cows.add(ids);
        }
    }

    static UUID spawnCow(ServerLevel level, BlockPos pos) {
        Cow cow = EntityType.COW.create(level);
        cow.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        cow.setNoAi(true);
        cow.setPersistenceRequired();
        level.addFreshEntity(cow);
        return cow.getUUID();
    }

    static void verifyMobs(ServerLevel level) {
        boolean removeExpected = ROUNDS[mobRound][2] == 1;
        int exposedAlive = 0, safeAlive = 0;
        for (UUID[] ids : cows) {
            if (level.getEntity(ids[0]) != null)
                exposedAlive++;
            for (int i = 1; i < 4; i++)
                if (level.getEntity(ids[i]) != null)
                    safeAlive++;
        }
        String detail = "unload day " + ROUNDS[mobRound][0] + " reload day " + ROUNDS[mobRound][1]
                + " exposedAlive=" + exposedAlive + "/" + cows.size() + " shelteredAlive=" + safeAlive + "/" + (cows.size() * 3);
        check("mobs round " + mobRound + (removeExpected ? " exposed removed" : " nobody removed"),
                (removeExpected ? exposedAlive == 0 : exposedAlive == cows.size()) && safeAlive == cows.size() * 3, detail);
        // clean up the survivors before the next round
        for (UUID[] ids : cows)
            for (UUID id : ids) {
                Entity e = level.getEntity(id);
                if (e != null)
                    e.discard();
            }
    }
}
