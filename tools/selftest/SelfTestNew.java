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
import net.minecraft.world.level.chunk.LevelChunk;
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
    // Water pool of each set of cows (the pool cow is exposed once it evaporates)
    static final List<BlockPos> pools = new ArrayList<>();
    // Spot under the roof of each set of cows
    static final List<BlockPos> roofs = new ArrayList<>();
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
        if (SelfTestCommon.enabled() && "multiplayer".equals(SelfTestCommon.scenario())) {
            try {
                multiplayerTick();
            } catch (Throwable t) {
                SelfTestCommon.log("EXCEPTION " + t);
                for (StackTraceElement el : t.getStackTrace())
                    SelfTestCommon.log("   at " + el);
                fails++;
                SelfTestCommon.finish();
            }
            return;
        }
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

    // ---------------------------------------------------------------- multiplayer

    /** A player logged in through a recording connection: what the mod sends it. */
    static final class Recorded {
        final net.minecraft.server.level.ServerPlayer player;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        final List<String> events = new ArrayList<>();

        Recorded(net.minecraft.server.level.ServerPlayer player, io.netty.channel.embedded.EmbeddedChannel channel) {
            this.player = player;
            this.channel = channel;
        }

        void drain() {
            Object msg;
            while ((msg = channel.readOutbound()) != null)
                record(msg);
        }

        void record(Object msg) {
            if (msg instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
                for (Object sub : bundle.subPackets())
                    record(sub);
            } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket payload
                    && payload.getIdentifier().getNamespace().equals("judgementday")) {
                net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(payload.getData().copy());
                int index = buf.readUnsignedByte();
                if (index == 1)
                    events.add("SYNC day=" + buf.readInt() + " max=" + buf.readInt() + " end=" + buf.readInt()
                            + " damage=" + buf.readInt() + " active=" + buf.readBoolean() + " delayed=" + buf.readBoolean()
                            + " dayCounter=" + buf.readBoolean() + " heatBar=" + buf.readBoolean());
                else if (index == 2)
                    events.add("TITLE " + buf.readEnum(com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase.class));
            } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundStopSoundPacket) {
                events.add("STOPSOUND");
            } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket sound) {
                events.add("SOUND " + sound.getSound().unwrapKey().map(k -> k.location().toString()).orElse("?"));
            }
        }

        boolean has(String prefix) {
            for (String e : events)
                if (e.startsWith(prefix))
                    return true;
            return false;
        }
    }

    static final List<Recorded> players = new ArrayList<>();
    static int mpDayBefore;

    static Recorded join(ServerLevel level, String name, int x) {
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name);
        net.minecraft.server.level.ServerPlayer player = new net.minecraft.server.level.ServerPlayer(SelfTestCommon.server, level, profile);
        player.moveTo(x, 200, 0, 0, 0);
        net.minecraft.network.Connection conn = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel(conn);
        SelfTestCommon.server.getPlayerList().placeNewPlayer(conn, player);
        Recorded r = new Recorded(player, channel);
        players.add(r);
        return r;
    }

    static void multiplayerTick() throws Exception {
        int tick = SelfTestCommon.tick;
        ServerLevel level = SelfTestCommon.server.getLevel(Level.OVERWORLD);
        for (Recorded r : players)
            r.drain();
        if (stage == 0 && tick >= 20) {
            SelfTestCommon.freezeWorld(level); // the clock only moves when the test sets it
            worldConfigs();
            defaults();
            ConfigManager.apocalypseCurrentDay = 9; // phase 1 starts on day 10 (50 days)
            Generator.resetState();
            level.setDayTime(1000);
            join(level, "PlayerOne", 0);
            join(level, "PlayerTwo", 400);
            stage = 1;
            waitUntil = tick + 5;
        } else if (stage == 1 && tick >= waitUntil) {
            for (Recorded r : players)
                check("multiplayer: " + r.player.getGameProfile().getName() + " gets the state on login",
                        r.has("SYNC day=9 max=50 end=200 damage=60 active=false"), r.events.toString());
            for (Recorded r : players)
                r.events.clear();
            // Two days of normal play left: a new day does not count
            ConfigManager.startDelayDays = 2;
            level.setDayTime(23990);
            stage = 10;
            waitUntil = tick + 2;
        } else if (stage == 10 && tick >= waitUntil) {
            level.setDayTime(24010);
            stage = 11;
            waitUntil = tick + 5;
        } else if (stage == 11 && tick >= waitUntil) {
            check("delay: the day stays put", ConfigManager.apocalypseCurrentDay == 9 && ConfigManager.startDelayDays == 1,
                    "day=" + ConfigManager.apocalypseCurrentDay + " delay=" + ConfigManager.startDelayDays);
            for (Recorded r : players)
                check("delay: " + r.player.getGameProfile().getName() + " hides the day counter",
                        r.has("SYNC day=9 max=50 end=200 damage=60 active=false delayed=true"), r.events.toString());
            for (Recorded r : players)
                r.events.clear();
            ConfigManager.startDelayDays = 0;
            ConfigManager.showHeatBar = false; // the server hides the heat bar
            level.setDayTime(47990);
            stage = 2;
            waitUntil = tick + 2;
        } else if (stage == 2 && tick >= waitUntil) {
            level.setDayTime(48010); // a new day
            stage = 3;
            waitUntil = tick + 5;
        } else if (stage == 3 && tick >= waitUntil) {
            check("multiplayer: the day changes once", ConfigManager.apocalypseCurrentDay == 10, "day=" + ConfigManager.apocalypseCurrentDay);
            for (Recorded r : players) {
                String n = r.player.getGameProfile().getName();
                check("multiplayer: " + n + " gets the new day", r.has("SYNC day=10 max=50 end=200 damage=48 active=false delayed=false dayCounter=true heatBar=false"), r.events.toString());
                check("multiplayer: " + n + " gets the phase title", r.has("TITLE PHASE_2"), r.events.toString());
                check("multiplayer: " + n + " gets the phase music", r.has("STOPSOUND") && r.has("SOUND "), r.events.toString());
            }
            SelfTestCommon.log("MP events " + players.get(0).events);
            // Everybody leaves: the apocalypse waits (pauseWhenEmpty, on by default)
            for (Recorded r : players)
                SelfTestCommon.server.getPlayerList().remove(r.player);
            players.clear();
            mpDayBefore = ConfigManager.apocalypseCurrentDay;
            level.setDayTime(71990);
            stage = 4;
            waitUntil = tick + 2;
        } else if (stage == 4 && tick >= waitUntil) {
            level.setDayTime(72010);
            stage = 5;
            waitUntil = tick + 5;
        } else if (stage == 5 && tick >= waitUntil) {
            check("multiplayer: the day stays put with nobody online", ConfigManager.pauseWhenEmpty
                    && SelfTestCommon.server.getPlayerCount() == 0 && ConfigManager.apocalypseCurrentDay == mpDayBefore,
                    "pause=" + ConfigManager.pauseWhenEmpty + " players=" + SelfTestCommon.server.getPlayerCount()
                            + " before=" + mpDayBefore + " now=" + ConfigManager.apocalypseCurrentDay);
            // Switched off: the days go on with nobody online
            ConfigManager.pauseWhenEmpty = false;
            level.setDayTime(95990);
            stage = 6;
            waitUntil = tick + 2;
        } else if (stage == 6 && tick >= waitUntil) {
            level.setDayTime(96010);
            stage = 7;
            waitUntil = tick + 5;
        } else if (stage == 7 && tick >= waitUntil) {
            check("multiplayer: days go on with nobody online when the pause is off", ConfigManager.apocalypseCurrentDay == mpDayBefore + 1,
                    "before=" + mpDayBefore + " now=" + ConfigManager.apocalypseCurrentDay);
            ConfigManager.pauseWhenEmpty = true;
            ConfigManager.showHeatBar = true;
            defaults();
            SelfTestCommon.log("RESULT fails=" + fails);
            SelfTestCommon.finish();
        }
    }

    /** Each world has its own config: created for a new world, taken over for an old one. */
    static void worldConfigs() throws Exception {
        java.nio.file.Path world = SelfTestCommon.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        java.nio.file.Path own = world.resolve("serverconfig").resolve("judgementdaymod-apocalypse.properties");
        check("config: the world has its own config", java.nio.file.Files.exists(own), own.toString());

        java.nio.file.Path base = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("cfgtest");
        deleteTree(base);
        java.nio.file.Path shared = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("judgementdaymod-apocalypse.properties");
        String sharedBefore = java.nio.file.Files.readString(shared);
        try {
            // Old shared config of a world on day 77
            java.nio.file.Files.writeString(shared, "apocalypseCurrentDay=77\napocalypseMaxDays=40\napocalypseEndDay=150\nminDamageHeight=30\nminWaterEvaporationHeight=30\nstartBeforeApocalypse=false\nlastAppliedPhase=5\napocalypseActive=true\n");

            // A new world: day 0 and the settings of the defaults file
            java.nio.file.Path fresh = base.resolve("fresh");
            java.nio.file.Files.createDirectories(fresh);
            ConfigManager.load(fresh);
            check("config: a new world starts on day 0 with the defaults", ConfigManager.apocalypseCurrentDay == 0
                    && ConfigManager.apocalypseMaxDays == 50 && !ConfigManager.apocalypseActive && ConfigManager.pauseWhenEmpty
                    && java.nio.file.Files.readString(fresh.resolve("serverconfig").resolve("judgementdaymod-apocalypse.properties")).contains("pauseWhenEmpty=true"),
                    "day=" + ConfigManager.apocalypseCurrentDay + " max=" + ConfigManager.apocalypseMaxDays + " active=" + ConfigManager.apocalypseActive);

            // A world the mod ran in before: carries on from the old shared config
            java.nio.file.Path old = base.resolve("old");
            java.nio.file.Files.createDirectories(old.resolve("data"));
            java.nio.file.Files.writeString(old.resolve("data").resolve("judgementday_chunkdata.dat"), "");
            ConfigManager.load(old);
            check("config: an old world carries on from the shared config", ConfigManager.apocalypseCurrentDay == 77
                    && ConfigManager.apocalypseMaxDays == 40 && ConfigManager.apocalypseActive && ConfigManager.lastAppliedPhase == 5,
                    "day=" + ConfigManager.apocalypseCurrentDay + " max=" + ConfigManager.apocalypseMaxDays);

            // Then each world keeps its own state
            ConfigManager.apocalypseCurrentDay = 78;
            ConfigManager.save();
            ConfigManager.load(fresh);
            int freshDay = ConfigManager.apocalypseCurrentDay;
            ConfigManager.load(old);
            check("config: each world keeps its own day", freshDay == 0 && ConfigManager.apocalypseCurrentDay == 78,
                    "fresh=" + freshDay + " old=" + ConfigManager.apocalypseCurrentDay);

            // An invalid value is ignored (the default stays)
            java.nio.file.Files.writeString(fresh.resolve("serverconfig").resolve("judgementdaymod-apocalypse.properties"),
                    "apocalypseMaxDays=abc\napocalypseCurrentDay=3\nstartBeforeApocalypse=false\nstartDelayDays=4\nshowHeatBar=false\npauseWhenEmpty=false\n");
            ConfigManager.load(fresh);
            check("config: invalid values ignored, new settings read", ConfigManager.apocalypseMaxDays == 50
                    && ConfigManager.apocalypseCurrentDay == 3 && ConfigManager.startDelayDays == 4 && !ConfigManager.showHeatBar && !ConfigManager.pauseWhenEmpty,
                    "max=" + ConfigManager.apocalypseMaxDays + " day=" + ConfigManager.apocalypseCurrentDay
                            + " delay=" + ConfigManager.startDelayDays + " heat=" + ConfigManager.showHeatBar + " pause=" + ConfigManager.pauseWhenEmpty);
        } finally {
            java.nio.file.Files.writeString(shared, sharedBefore);
            ConfigManager.load(world);
            deleteTree(base);
        }
    }

    static void deleteTree(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.exists(dir))
            return;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                java.nio.file.Files.delete(p);
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
            heightsAfterJump();
            snowAndIce(level);
            meltOnTop(level);
            noFireAfterEnd(level);
            tallTrees(level);
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
                    // A player arrives: the chunk catches up on the steps it missed
                    com.creatormc.judgementdaymod.handlers.DayTracker
                            .enqueueChunk(new ChunkToProcess(level, MOB_CHUNKS[i], MOB_CHUNKS[i + 1]));
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
        Generator.handleDayEvent();
        check("restart keeps heights", ConfigManager.minDamageHeight == 30 && ConfigManager.minWaterEvaporationHeight == 30,
                "damage=" + ConfigManager.minDamageHeight + " water=" + ConfigManager.minWaterEvaporationHeight);

        // Second restart on the same phase
        Generator.resetState();
        ConfigManager.apocalypseCurrentDay = 28;
        Generator.handleDayEvent();
        check("second restart keeps heights", ConfigManager.minDamageHeight == 30, "damage=" + ConfigManager.minDamageHeight);

        // Next phase is applied once (30 - 3 * 6 = 12)
        ConfigManager.apocalypseCurrentDay = 30;
        Generator.handleDayEvent();
        check("next phase applied", ConfigManager.minDamageHeight == 12 && ConfigManager.lastAppliedPhase == 3,
                "damage=" + ConfigManager.minDamageHeight + " phase=" + ConfigManager.lastAppliedPhase);
        Generator.resetState();
        ConfigManager.apocalypseCurrentDay = 31;
        Generator.handleDayEvent();
        check("phase not applied twice after restart", ConfigManager.minDamageHeight == 12, "damage=" + ConfigManager.minDamageHeight);

        // Fresh world: day 0 -> the first day change enters phase 0 (title/music), heights unchanged
        defaults();
        ConfigManager.apocalypseCurrentDay = 1;
        Generator.handleDayEvent();
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
                    Generator.handleDayEvent();
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
        // Ten pools: the step itself takes the top block of a column 20% of the time, the
        // evaporation would take them all
        BlockPos[] pools = new BlockPos[10];
        for (int i = 0; i < pools.length; i++) {
            pools[i] = new BlockPos((cx << 4) + 1 + i, 100, (cz << 4) + 8);
            level.setBlock(pools[i].below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(pools[i], Blocks.WATER.defaultBlockState(), 2);
        }
        ApocalypseChunkData.get(level).setPhase(new ChunkPos(cx, cz), 0);
        ConfigManager.apocalypseCurrentDay = 12;
        ConfigManager.lastApocalypseStep = 1;
        ConfigManager.apocalypseEndDay = 12;
        ConfigManager.apocalypseMaxDays = 11;
        Generator.processChunk(new ChunkToProcess(level, cx, cz));
        int kept = 0;
        for (BlockPos pool : pools)
            if (level.getBlockState(pool).is(Blocks.WATER))
                kept++;
        check("ritual at step 1 keeps water", kept >= 3, "pools kept=" + kept + "/" + pools.length);
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
    // ------------------------------------------------------------------ 1.3.0

    static void heightsAfterJump() {
        // Day moved forward by hand from 25 (phase 2 applied) to 200: the skipped phases
        // lower the heights at load as day by day (30 -> 12 -> 4 -> 4)
        defaults();
        ConfigManager.apocalypseCurrentDay = 200;
        ConfigManager.minDamageHeight = 30;
        ConfigManager.minWaterEvaporationHeight = 30;
        ConfigManager.lastAppliedPhase = 2;
        Generator.resetState();
        check("jump 25 -> 200 lowers the skipped heights", ConfigManager.minDamageHeight == 4
                && ConfigManager.minWaterEvaporationHeight == 4 && ConfigManager.lastAppliedPhase == 5,
                "damage=" + ConfigManager.minDamageHeight + " water=" + ConfigManager.minWaterEvaporationHeight
                        + " phase=" + ConfigManager.lastAppliedPhase);

        // Within the countdown: 25 -> 45
        defaults();
        ConfigManager.apocalypseCurrentDay = 45;
        ConfigManager.minDamageHeight = 30;
        ConfigManager.minWaterEvaporationHeight = 30;
        ConfigManager.lastAppliedPhase = 2;
        Generator.resetState();
        check("jump 25 -> 45 lowers the skipped heights", ConfigManager.minDamageHeight == 4
                && ConfigManager.lastAppliedPhase == 4,
                "damage=" + ConfigManager.minDamageHeight + " phase=" + ConfigManager.lastAppliedPhase);

        // After the ritual (day 30, phase 3 applied) the heights stay as they were then
        defaults();
        ConfigManager.apocalypseCurrentDay = 30;
        ConfigManager.apocalypseMaxDays = 29;
        ConfigManager.apocalypseEndDay = 30;
        ConfigManager.minDamageHeight = 12;
        ConfigManager.minWaterEvaporationHeight = 12;
        ConfigManager.lastAppliedPhase = 3;
        ConfigManager.lastApocalypseStep = 3;
        Generator.resetState();
        check("ritual keeps its heights at load", ConfigManager.minDamageHeight == 12 && ConfigManager.lastAppliedPhase == 3,
                "damage=" + ConfigManager.minDamageHeight + " phase=" + ConfigManager.lastAppliedPhase);

        // Normal play: nothing to catch up, phase 0 still entered on day 1
        defaults();
        check("fresh world untouched at load", ConfigManager.lastAppliedPhase == -1 && ConfigManager.minDamageHeight == 60,
                "phase=" + ConfigManager.lastAppliedPhase);
        ConfigManager.apocalypseCurrentDay = 26;
        ConfigManager.minDamageHeight = 30;
        ConfigManager.lastAppliedPhase = 2;
        Generator.resetState();
        check("restart on the phase of the day untouched", ConfigManager.minDamageHeight == 30 && ConfigManager.lastAppliedPhase == 2,
                "damage=" + ConfigManager.minDamageHeight);
        defaults();
    }

    /** A cold surface spot: x, z of a snowy biome, loaded. */
    static BlockPos coldSpot(ServerLevel level, int skip) {
        com.mojang.datafixers.util.Pair<BlockPos, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> found = level
                .findClosestBiome3d(h -> h.is(net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS)
                        || h.is(net.minecraft.world.level.biome.Biomes.SNOWY_TAIGA)
                        || h.is(net.minecraft.world.level.biome.Biomes.ICE_SPIKES), new BlockPos(0, 64, 0), 6400, 32, 64);
        if (found == null)
            return null;
        BlockPos p = found.getFirst().offset(skip * 16, 0, 0);
        level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
        level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        return p;
    }

    static void snowAndIce(ServerLevel level) {
        BlockPos spot = coldSpot(level, 0);
        if (spot == null) {
            check("snow: cold biome found", false, "");
            return;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spot.getX(), spot.getZ()) + 4;
        BlockPos snowPos = new BlockPos(spot.getX(), top, spot.getZ());
        BlockPos waterPos = snowPos.east(2);
        for (BlockPos p : new BlockPos[] { snowPos, waterPos }) {
            level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 0; dy < 6; dy++)
                level.setBlock(p.above(dy), Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(waterPos, Blocks.WATER.defaultBlockState(), 2);
        net.minecraft.world.level.biome.Biome biome = level.getBiome(snowPos).value();
        defaults();
        ConfigManager.apocalypseCurrentDay = -5;
        boolean snowBefore = biome.shouldSnow(level, snowPos);
        boolean iceBefore = level.getBiome(waterPos).value().shouldFreeze(level, waterPos, false);
        ConfigManager.apocalypseCurrentDay = 0;
        ConfigManager.startDelayDays = 3;
        boolean snowDelay = biome.shouldSnow(level, snowPos);
        ConfigManager.startDelayDays = 0;
        boolean snowAfter = biome.shouldSnow(level, snowPos);
        boolean iceAfter = level.getBiome(waterPos).value().shouldFreeze(level, waterPos, false);
        check("snow settles and water freezes during the preparation and the delay", snowBefore && iceBefore && snowDelay,
                "snow=" + snowBefore + " ice=" + iceBefore + " delay=" + snowDelay + " at " + snowPos.toShortString());
        check("no new snow or ice from day 0", !snowAfter && !iceAfter, "snow=" + snowAfter + " ice=" + iceAfter);
        defaults();
    }

    static void meltOnTop(ServerLevel level) {
        for (int round = 0; round < 3; round++) {
            BlockPos spot = coldSpot(level, 1 + round);
            if (spot == null)
                return;
            int cx = spot.getX() >> 4, cz = spot.getZ() >> 4;
            int bx = cx << 4, bz = cz << 4;
            int y0 = level.getMaxBuildHeight() - 40;
            // Snow on grass (a row of them, the steps may turn some grass into ash), ice
            // over water in a basin, a packed ice pillar: each the top of its column
            BlockPos snow = new BlockPos(bx + 2, y0, bz + 2);
            for (int i = 0; i < 8; i++) {
                BlockPos g = snow.south(i);
                level.setBlock(g.below(), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(g, Blocks.GRASS_BLOCK.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY, true), 2);
                level.setBlock(g.above(), Blocks.SNOW.defaultBlockState(), 2);
            }
            BlockPos ice = new BlockPos(bx + 8, y0, bz + 8);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(ice.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 2);
                    if (dx != 0 || dz != 0)
                        level.setBlock(ice.offset(dx, 1, dz), Blocks.STONE.defaultBlockState(), 2);
                }
            level.setBlock(ice.above(), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(ice.above(2), Blocks.ICE.defaultBlockState(), 2);
            BlockPos pillar = new BlockPos(bx + 13, y0, bz + 13);
            level.setBlock(pillar, Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 1; dy <= 3; dy++)
                level.setBlock(pillar.above(dy), Blocks.PACKED_ICE.defaultBlockState(), 2);

            defaults();
            // round 0: day -5 (preparation, nothing melts); round 1: day 5 on a chunk already
            // at step 0 (not transformed, only melted); round 2: day 10 (step 1)
            ApocalypseChunkData.get(level).setPhase(new ChunkPos(cx, cz), round == 1 ? 0 : -1);
            ConfigManager.apocalypseCurrentDay = round == 0 ? -5 : round == 1 ? 5 : 10;
            Generator.resetState();
            Generator.processChunk(new ChunkToProcess(level, cx, cz));

            boolean snowGone = true, groundClear = true;
            int grassLeft = 0, snowLeft = 0;
            for (int i = 0; i < 8; i++) {
                if (level.getBlockState(snow.south(i).above()).is(Blocks.SNOW)) {
                    snowGone = false;
                    snowLeft++;
                }
                net.minecraft.world.level.block.state.BlockState g = level.getBlockState(snow.south(i));
                if (g.is(Blocks.GRASS_BLOCK))
                    grassLeft++;
                if (g.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
                        && g.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY))
                    groundClear = false;
            }
            net.minecraft.world.level.block.state.BlockState ground = level.getBlockState(snow);
            net.minecraft.world.level.block.state.BlockState iceTop = level.getBlockState(ice.above(2));
            boolean iceMelted = !iceTop.is(Blocks.ICE);
            boolean iceToWater = iceTop.is(Blocks.WATER) || iceTop.isAir();
            int packed = 0;
            for (int dy = 1; dy <= 3; dy++)
                if (level.getBlockState(pillar.above(dy)).is(Blocks.PACKED_ICE))
                    packed++;
            String detail = "snowLeft=" + snowLeft + " ground=" + ground + " grassLeft=" + grassLeft + " iceTop=" + iceTop
                    + " packedLeft=" + packed;
            if (round == 0) {
                check("melt: nothing melts during the preparation", snowLeft == 8 && packed == 3, detail);
            } else if (round == 1) {
                check("melt: from day 0 a chunk already at its step melts too",
                        snowGone && groundClear && grassLeft == 8 && iceMelted && iceToWater && packed == 0, detail);
            } else {
                check("melt: snow, ice and packed ice on top melt when the step comes",
                        snowGone && groundClear && grassLeft > 0 && iceMelted && iceToWater && packed == 0, detail);
            }
            level.setChunkForced(cx, cz, false);
        }
        defaults();
    }

    static int countFire(LevelChunk chunk) {
        int fires = 0;
        for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections())
            if (section != null && !section.hasOnlyAir() && section.maybeHas(st -> st.is(Blocks.FIRE)))
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            if (section.getBlockState(x, y, z).is(Blocks.FIRE))
                                fires++;
        return fires;
    }

    static void noFireAfterEnd(ServerLevel level) {
        com.mojang.datafixers.util.Pair<BlockPos, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> found = level
                .findClosestBiome3d(h -> h.is(net.minecraft.tags.BiomeTags.IS_FOREST), new BlockPos(0, 64, 0), 6400, 32, 64);
        if (found == null) {
            check("fire: forest found", false, "");
            return;
        }
        int[] fires = new int[2];
        for (int round = 0; round < 2; round++) {
            int cx = (found.getFirst().getX() >> 4) + round * 3, cz = found.getFirst().getZ() >> 4;
            level.setChunkForced(cx, cz, true);
            LevelChunk chunk = level.getChunk(cx, cz);
            defaults();
            ApocalypseChunkData.get(level).setPhase(new ChunkPos(cx, cz), 2);
            // round 0: day 30, before the end; round 1: day 200, after it
            ConfigManager.apocalypseCurrentDay = round == 0 ? 30 : 200;
            Generator.resetState();
            Generator.processChunk(new ChunkToProcess(level, cx, cz));
            fires[round] = countFire(chunk);
            level.setChunkForced(cx, cz, false);
        }
        check("fire: a forest catches fire before the end", fires[0] > 0, "fires=" + fires[0]);
        check("fire: no fire when catching up after the end", fires[1] == 0, "fires=" + fires[1]);
        defaults();
    }

    /**
     * A 25 block trunk under leaves, and leaves over a gap over a trunk: one step burns
     * each column down to the ground, leaving the fire on it (none after the end).
     */
    static void tallTrees(ServerLevel level) {
        for (int round = 0; round < 2; round++) {
            int cx = 240 + round * 3, cz = 240;
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
            int bx = (cx << 4) + 8, bz = (cz << 4) + 8;
            int y0 = level.getMaxBuildHeight() - 80;
            BlockPos[] cols = { new BlockPos(bx, y0, bz), new BlockPos(bx + 3, y0, bz) };
            for (BlockPos c : cols) {
                level.setBlock(c.below(), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(c, Blocks.DIRT.defaultBlockState(), 2);
                for (int dy = 1; dy <= 40; dy++)
                    level.setBlock(c.above(dy), Blocks.AIR.defaultBlockState(), 2);
            }
            for (int dy = 1; dy <= 25; dy++)
                level.setBlock(cols[0].above(dy), Blocks.SPRUCE_LOG.defaultBlockState(), 2);
            for (int dy = 26; dy <= 28; dy++)
                level.setBlock(cols[0].above(dy), Blocks.SPRUCE_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
            for (int dy = 1; dy <= 10; dy++)
                level.setBlock(cols[1].above(dy), Blocks.OAK_LOG.defaultBlockState(), 2);
            for (int dy = 15; dy <= 17; dy++)
                level.setBlock(cols[1].above(dy), Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
            defaults();
            // round 0: one step at 100% (day 50); round 1: catching up after the end
            ApocalypseChunkData.get(level).setPhase(new ChunkPos(cx, cz), 4);
            ConfigManager.apocalypseCurrentDay = round == 0 ? 50 : 200;
            Generator.resetState();
            Generator.processChunk(new ChunkToProcess(level, cx, cz));
            int wood = 0, fires = 0;
            for (BlockPos c : cols)
                for (int dy = 1; dy <= 40; dy++) {
                    net.minecraft.world.level.block.state.BlockState st = level.getBlockState(c.above(dy));
                    if (st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES))
                        wood++;
                    if (st.is(Blocks.FIRE))
                        fires++;
                }
            boolean fireOnGround = level.getBlockState(cols[0].above()).is(Blocks.FIRE)
                    && level.getBlockState(cols[1].above()).is(Blocks.FIRE);
            String detail = "woodLeft=" + wood + " fires=" + fires + " fireOnGround=" + fireOnGround
                    + " ground=" + level.getBlockState(cols[0]).getBlock();
            if (round == 0) {
                check("trees: a tall trunk burns down in one step, the fire on the ground", wood == 0 && fireOnGround && fires == 2, detail);
            } else {
                check("trees: after the end a tall trunk is gone without fire", wood == 0 && fires == 0, detail);
            }
            level.setChunkForced(cx, cz, false);
        }
        defaults();
    }

    static void setupMobs(ServerLevel level) {
        cows.clear();
        pools.clear();
        roofs.clear();
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
            pools.add(pool);
            // roof
            BlockPos roofed = new BlockPos(bx + 5, top, bz + 5);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlock(roofed.offset(dx, 3, dz), Blocks.STONE.defaultBlockState(), 2);
            ids[3] = spawnCow(level, roofed);
            roofs.add(roofed);
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
        int exposedAlive = 0, safeAlive = 0, safeExpected = 0, dryPools = 0;
        for (int c = 0; c < cows.size(); c++) {
            UUID[] ids = cows.get(c);
            if (level.getEntity(ids[0]) != null)
                exposedAlive++;
            // The pool cow is in the open once its chunk caught up and the pool evaporated:
            // judged again then (1.3.0)
            boolean dry = !level.getBlockState(pools.get(c)).is(Blocks.WATER);
            if (dry)
                dryPools++;
            // The roof turns into ash when its chunk catches up, which falls: in the open then
            BlockPos roof = roofs.get(c);
            boolean roofGone = level.getBlockState(roof.above(3)).isAir() && level.getBlockState(roof.above(2)).isAir()
                    && level.getBlockState(roof.above()).isAir();
            safeExpected += 3 - (removeExpected && dry ? 1 : 0) - (removeExpected && roofGone ? 1 : 0);
            for (int i = 1; i < 4; i++)
                if (level.getEntity(ids[i]) != null)
                    safeAlive++;
            SelfTestCommon.log("DEBUG set " + c + " alive=" + (level.getEntity(ids[0]) != null) + "," + (level.getEntity(ids[1]) != null)
                    + "," + (level.getEntity(ids[2]) != null) + "," + (level.getEntity(ids[3]) != null) + " poolDry=" + dry
                    + " roofGone=" + roofGone + " roof=" + level.getBlockState(roof.above(3)).getBlock() + "/"
                    + level.getBlockState(roof.above(2)).getBlock() + "/" + level.getBlockState(roof.above()).getBlock()
                    + "/" + level.getBlockState(roof).getBlock());
        }
        String detail = "unload day " + ROUNDS[mobRound][0] + " reload day " + ROUNDS[mobRound][1]
                + " exposedAlive=" + exposedAlive + "/" + cows.size() + " shelteredAlive=" + safeAlive + "/" + safeExpected
                + " dryPools=" + dryPools;
        check("mobs round " + mobRound + (removeExpected ? " exposed removed" : " nobody removed"),
                (removeExpected ? exposedAlive == 0 : exposedAlive == cows.size()) && safeAlive == safeExpected, detail);
        // clean up the survivors before the next round
        for (UUID[] ids : cows)
            for (UUID id : ids) {
                Entity e = level.getEntity(id);
                if (e != null)
                    e.discard();
            }
    }
}
