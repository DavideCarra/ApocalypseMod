package com.creatormc.judgementdaymod.selftest;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.creatormc.judgementdaymod.handlers.ApocalypseDamageHandler;
import com.creatormc.judgementdaymod.handlers.DayTracker;
import com.creatormc.judgementdaymod.handlers.NetworkHandler;
import com.creatormc.judgementdaymod.models.PhaseTitlePacket;
import com.creatormc.judgementdaymod.setup.ApocalypseChunkGenerator;
import com.creatormc.judgementdaymod.setup.ModBlocks;
import com.creatormc.judgementdaymod.setup.ModItems;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.creatormc.judgementdaymod.utilities.ChunkLightSync;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Single player scenario (not part of the mod): runs in the integrated server of a real
 * client (scenario=single, see runsingle.py) and checks, with the real client of the same
 * process, what a dedicated server test cannot: the state the client draws against the
 * server's, a new day and a phase change, the heat, death and respawn, the pause, the
 * blocks and light of the real client against the server's, the rituals and the buckets
 * done with real mouse clicks, and leaving the world for another one in the same game
 * session.
 *
 * The clicks and the screenshots are done by the script that started the game: the scenario
 * asks for them with a file in the game directory (sp.request).
 */
public class SelfTestSingle {

    interface Action {
        void run() throws Exception;
    }

    interface Cond {
        boolean test() throws Exception;
    }

    interface Text {
        String get() throws Exception;
    }

    /** A step of the scenario: called every server tick until it returns true. */
    interface Step {
        boolean run() throws Exception;
    }

    // Integrated servers started in this game session (1: first world, 2: second world)
    static int session;
    static final List<Step> steps = new ArrayList<>();
    static int at;
    static int stepStart;
    static int t;

    static MinecraftServer server;
    static ServerLevel level;
    static ServerPlayer player;
    static String name;

    static int dayBefore;
    static int quietTicks;
    // The player of the first world (the heat of the second one starts without them, 1.3.5)
    static UUID firstPlayer;

    // The arena: a stone floor in the air (the player stands at y 150), far from the spawn
    static final int AX = 1000, AY = 150, AZ = 1000;

    /** A new integrated server started (called by SelfTestCommon). */
    static void started() {
        session++;
        steps.clear();
        at = 0;
        t = 0;
        stepStart = 0;
        SelfTestCommon.log("SESSION " + session);
        SelfTestClient.register();
        if (session == 2) {
            // Before anybody joins: nothing of the first world is left on the server (1.3.5)
            try {
                check("sp: the second world starts without the heat of the first one",
                        firstPlayer != null && heatOf(firstPlayer) == null,
                        "heat=" + (firstPlayer == null ? "player unknown" : heatWord(heatOf(firstPlayer) == null ? -1f : heatOf(firstPlayer))));
            } catch (Exception e) {
                check("sp: the second world starts without the heat of the first one", false, e.toString());
            }
        }
        if (session == 1)
            firstWorld();
        else
            secondWorld();
    }

    static void tick() {
        try {
            server = SelfTestCommon.server;
            level = server.overworld();
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            t++;
            if (players.isEmpty()) {
                if (t > 6000) {
                    check("sp: the player joins the world", false, "nobody after 5 minutes");
                    end(true);
                }
                return;
            }
            // The player is another object after a respawn
            player = players.get(0);
            name = player.getGameProfile().getName();
            if (session == 1)
                firstPlayer = player.getUUID();
            if (at >= steps.size()) {
                end(false);
                return;
            }
            if (steps.get(at).run()) {
                at++;
                stepStart = t;
            }
        } catch (Throwable e) {
            log("EXCEPTION " + e);
            for (StackTraceElement el : e.getStackTrace())
                log("   at " + el);
            SelfTestNew.fails++;
            end(true);
        }
    }

    // ---------------------------------------------------------------- steps

    static void now(Action a) {
        steps.add(() -> {
            a.run();
            return true;
        });
    }

    static void waitTicks(int n) {
        steps.add(() -> t - stepStart >= n);
    }

    /** Waits for the condition; a check fails if it does not come true in time. */
    static void until(String what, Cond c, int timeout) {
        steps.add(() -> {
            if (c.test())
                return true;
            if (t - stepStart < timeout)
                return false;
            check("sp: " + what, false, "not after " + timeout + " ticks");
            return true;
        });
    }

    /** A check that may need a moment to come true (what the client has arrives a little later). */
    static void expect(String what, Cond c, int within, Text detail) {
        steps.add(() -> {
            boolean ok = c.test();
            if (!ok && t - stepStart < within)
                return false;
            check("sp: " + what, ok, detail.get());
            return true;
        });
    }

    /** Asks the script that started the game for a mouse click or a screenshot, and waits for it. */
    static void ask(String request) {
        now(() -> {
            Path dir = SelfTestCommon.gameDir();
            Files.writeString(dir.resolve("sp.request.tmp"), request);
            Files.move(dir.resolve("sp.request.tmp"), dir.resolve("sp.request"), StandardCopyOption.ATOMIC_MOVE);
        });
        until("the script does '" + request + "'", () -> !Files.exists(SelfTestCommon.gameDir().resolve("sp.request")), 600);
    }

    static void click() {
        ask("click 3");
        // The click reaches the client, then the server
        waitTicks(30);
    }

    static void shot(String shotName) {
        final int world = session;
        ask("shot " + world + "_" + shotName);
    }

    /** The client has what the server has. */
    static void sameState(String what) {
        expect(what, () -> serverState().equals(SelfTestClient.state()), 60,
                () -> "server[" + serverState() + "] client[" + SelfTestClient.state() + "]");
    }

    /**
     * The world stands still for a second, then the game is paused and the real client's
     * blocks and light are compared with the server's.
     */
    static void compare(String where) {
        now(() -> quietTicks = 0);
        steps.add(() -> {
            quietTicks = quiet() ? quietTicks + 1 : 0;
            if (quietTicks < 20 && t - stepStart < 2400)
                return false;
            log("QUIET " + where + ": " + (quietTicks >= 20 ? "yes" : "NO, after " + (t - stepStart) + " ticks"));
            SelfTestClient.pauseAndCompare(level, 3);
            return true;
        });
        steps.add(() -> {
            SelfTestClient.PauseResult r = SelfTestClient.pauseResult;
            if (r == null) {
                if (t - stepStart < 2400)
                    return false;
                check("sp: the game pauses (" + where + ")", false, "no pause after two minutes of ticks");
                return true;
            }
            check("sp: nothing runs while the game is paused (" + where + ")", r.error == null && r.paused && r.ticks == 0
                    && r.dayTime == 0 && r.day == 0 && r.heat == 0f,
                    "paused=" + r.paused + " serverTicks=" + r.ticks + " dayTime=" + r.dayTime + " day=" + r.day + " heat="
                            + r.heat + (r.error == null ? "" : " error=" + r.error));
            String[] parts = r.compare == null ? new String[] { "no comparison", "" } : r.compare.split("\n");
            check("sp: the real client has the server's blocks (" + where + ")", r.compare != null
                    && parts[0].contains(" missing=0 ") && parts[0].endsWith(" blockDiffs=0"), parts[0]);
            // The fires lit by the processing do not glow on the client either (1.3.5): the client has no
            // block light the server does not have. The rest of the light is reported: the client relights
            // the blocks it gets by itself, and in vanilla too its light can be a level off here and there
            // (see README)
            check("sp: no glow on the client that the server does not have (" + where + ")", r.compare != null
                    && r.lightQuiet && blockLightBrighter(parts[1]) == 0, "lightQuiet=" + r.lightQuiet + " " + parts[1]);
            log("LIGHT " + where + ": lightQuiet=" + r.lightQuiet + " " + parts[1]);
            return true;
        });
        waitTicks(20);
    }

    // ---------------------------------------------------------------- helpers

    static void check(String what, boolean ok, String detail) {
        SelfTestNew.check(what, ok, detail);
    }

    static void log(String s) {
        SelfTestCommon.log(s);
    }

    static void cmd(String c) {
        MinecraftServer s = SelfTestCommon.server;
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), c);
    }

    static void writeOut() {
        try {
            Files.write(SelfTestCommon.gameDir().resolve(SelfTestCommon.props.getProperty("output", "selftest-out.txt")),
                    SelfTestCommon.out);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static String serverState() {
        return "day=" + ConfigManager.apocalypseCurrentDay + " max=" + ConfigManager.apocalypseMaxDays + " end="
                + ConfigManager.apocalypseEndDay + " damage=" + ConfigManager.minDamageHeight + " active="
                + ConfigManager.apocalypseActive + " delayed=" + (ConfigManager.startDelayDays > 0) + " counter="
                + ConfigManager.showDayCounter + " heatbar=" + ConfigManager.showHeatBar;
    }

    @SuppressWarnings("unchecked")
    static Float heatOf(UUID id) throws Exception {
        Field f = ApocalypseDamageHandler.class.getDeclaredField("heatLevels");
        f.setAccessible(true);
        return ((Map<UUID, Float>) f.get(null)).get(id);
    }

    /** The heat the server has for the player (-1: none). */
    static float heat() throws Exception {
        Float h = heatOf(player.getUUID());
        return h == null ? -1f : h;
    }

    static String heatWord(float h) {
        return h > 0f ? "above 0" : h == 0f ? "0" : "none";
    }

    static Object field(Class<?> cls, String field) throws Exception {
        Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    static boolean empty(Object collection) {
        if (collection instanceof Collection<?> c)
            return c.isEmpty();
        return ((Map<?, ?>) collection).isEmpty();
    }

    /** The block light values the client has higher than the server, in a comparison line. */
    static long blockLightBrighter(String light) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("blockLight: differ=\\d+ insideSolidBlocks=\\d+ seen=\\d+ \\(clientBrighter=(\\d+)").matcher(light);
        return m.find() ? Long.parseLong(m.group(1)) : light.contains("blockLight: differ=") ? 0 : -1;
    }

    /** Empty, or not in this version of the mod. */
    static boolean emptyIfAny(Class<?> cls, String field) throws Exception {
        try {
            return empty(field(cls, field));
        } catch (NoSuchFieldException e) {
            return true;
        }
    }

    /** Nothing of the mod is waiting, the light is computed and no block is falling around the player. */
    static boolean quiet() throws Exception {
        return empty(field(DayTracker.class, "chunksToProcess")) && empty(field(DayTracker.class, "carriedOver"))
                && empty(field(ChunkLightSync.class, "pending")) && empty(field(ChunkLightSync.class, "lit"))
                && empty(field(ChunkLightSync.class, "spills")) && emptyIfAny(ChunkLightSync.class, "followUps")
                && !level.getChunkSource().getLightEngine().hasLightWork()
                && level.getEntitiesOfClass(FallingBlockEntity.class, player.getBoundingBox().inflate(96)).isEmpty();
    }

    static boolean is(int x, int y, int z, Block block) {
        return level.getBlockState(new BlockPos(x, y, z)).is(block);
    }

    static String blockAt(int x, int y, int z) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock())
                .toString();
    }

    static int hearts() {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(AX - 12, AY - 10, AZ - 12, AX + 13, AY + 20, AZ + 13),
                e -> e.getItem().is(ModItems.HEART_OF_OBLIVION.get())).size();
    }

    /** The hearts carried by the player. */
    static int carried() {
        return player.getInventory().countItem(ModItems.HEART_OF_OBLIVION.get());
    }

    static String hand() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
    }

    static boolean pillarGone() {
        boolean air = true;
        for (int y = 150; y <= 153; y++)
            air &= level.getBlockState(new BlockPos(1000, y, 1000)).isAir();
        return air;
    }

    static String clientPillar() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int y = 150; y <= 153; y++)
            sb.append(y > 150 ? "," : "").append(SelfTestClient.blockAt(1000, y, 1000));
        return sb.toString();
    }

    static void arena() {
        cmd("fill 990 149 990 1010 149 1010 minecraft:stone");
        cmd("fill 990 150 990 1010 157 1010 minecraft:air");
        // A roofed corner: the player respawns there, out of the sun
        cmd("fill 1006 153 1006 1008 153 1008 minecraft:stone");
    }

    static void aimFloor() {
        // Looking north and down at the top of the floor block 1000 149 998
        cmd("tp " + name + " 1000.5 150 1000.5 180 39");
    }

    static void aimPillar() {
        // Two blocks south of the pillar, looking at the south face of its lowest block
        cmd("tp " + name + " 1000.5 150 1002.5 180 35");
    }

    static void give(String item) {
        cmd("item replace entity " + name + " weapon.mainhand with " + item);
    }

    static void effects() {
        cmd("effect give " + name + " minecraft:resistance infinite 4 true");
        cmd("effect give " + name + " minecraft:fire_resistance infinite 0 true");
    }

    static void rules() {
        for (String c : new String[] { "gamerule doMobSpawning false", "gamerule doFireTick false",
                "gamerule randomTickSpeed 0", "gamerule doWeatherCycle false", "weather clear",
                "gamerule doImmediateRespawn true", "gamemode survival " + name, "clear " + name })
            cmd(c);
        effects();
    }

    /** The arena is built once its chunks are loaded and processed for the day, with the player on it. */
    static void goToArena() {
        now(() -> cmd("forceload add 992 992 1008 1008"));
        until("the arena chunks load", () -> level.getChunkSource().getChunkNow(AX >> 4, AZ >> 4) != null, 1200);
        now(() -> {
            arena();
            aimFloor();
            quietTicks = 0;
        });
        steps.add(() -> {
            quietTicks = quiet() ? quietTicks + 1 : 0;
            return (quietTicks >= 20 && t - stepStart >= 100) || t - stepStart >= 2400;
        });
        now(() -> {
            arena();
            cmd("kill @e[type=minecraft:item]");
            cmd("kill @e[type=minecraft:falling_block]");
            cmd("spawnpoint " + name + " 1007 150 1007");
            aimFloor();
        });
        waitTicks(30);
    }

    // ---------------------------------------------------------------- first world

    static void firstWorld() {
        // The effects first: the sun must not kill the player during the test
        now(() -> rules());
        // The client gets its chunks
        waitTicks(160);
        now(() -> {
            check("sp: integrated server of a single player world", !server.isDedicatedServer() && server.isSingleplayer(), "");
            check("sp: apocalypse generator installed",
                    level.getChunkSource().getGenerator() instanceof ApocalypseChunkGenerator, "");
            Path cfg = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig")
                    .resolve("judgementdaymod-apocalypse.properties").toAbsolutePath().normalize();
            check("sp: config of the world in its save folder", Files.exists(cfg),
                    SelfTestCommon.gameDir().toAbsolutePath().normalize().relativize(cfg).toString().replace('\\', '/'));
        });
        sameState("at login the client has the server's state");
        now(() -> log("HUD at login " + SelfTestClient.hud()));
        shot("0_login");
        // The light before the apocalypse changes anything (day 9: nothing to transform)
        compare("at login");

        // --- a new day by the clock: dawn comes in a second
        now(() -> {
            dayBefore = ConfigManager.apocalypseCurrentDay;
            level.setDayTime(23980);
        });
        until("the day changes at dawn", () -> ConfigManager.apocalypseCurrentDay != dayBefore, 400);
        now(() -> {
            int day = ConfigManager.apocalypseCurrentDay;
            check("sp: a new day starts at dawn", day == dayBefore + 1, "day " + dayBefore + " -> " + day);
            check("sp: the phase of the new day is applied", ConfigManager.lastAppliedPhase == 1
                    && ConfigManager.minDamageHeight == 48 && ConfigManager.minWaterEvaporationHeight == 48,
                    "lastAppliedPhase=" + ConfigManager.lastAppliedPhase + " damage=" + ConfigManager.minDamageHeight
                            + " water=" + ConfigManager.minWaterEvaporationHeight);
            Phase phase = Phase.getPhaseForPercent(Phase.toPercent(day, ConfigManager.apocalypseMaxDays));
            check("sp: the music of the phase is played", field(Generator.class, "lastPlayedPhase") == phase,
                    "lastPlayedPhase=" + field(Generator.class, "lastPlayedPhase"));
        });
        sameState("the client has the new day");
        expect("the client shows the title of the phase", () -> SelfTestClient.title(Phase.PHASE_2).startsWith("same "), 40,
                () -> SelfTestClient.title(Phase.PHASE_2));
        now(() -> log("HUD after the new day " + SelfTestClient.hud()));
        shot("1_phase_title");

        // --- the chunks around the player were processed for the new step under the client's eyes
        compare("at the spawn after the phase change");
        shot("2_spawn_after_phase");

        // --- the heat: in the open, at noon, the heat of the player rises (0.4 a second at 20%)
        goToArena();
        now(() -> cmd("time set 6000"));
        expect("the sun heats the player in the open", () -> heat() >= 7f, 1200, () -> "heat>=7: " + (heat() >= 7f));
        expect("the heat bar has the server's heat", () -> heat() == SelfTestClient.heat(), 60,
                () -> "equal=" + (heat() == SelfTestClient.heat()) + " server>=7: " + (heat() >= 7f) + " client>=7: "
                        + (SelfTestClient.heat() >= 7f));
        expect("the player burns from heat 7", () -> player.getRemainingFireTicks() > 0, 0,
                () -> "onFire=" + (player.getRemainingFireTicks() > 0));
        now(() -> log("HUD while hot " + SelfTestClient.hud()));
        shot("3_heat");

        // --- death and respawn: at night under the roof of the spawn point the heat stays where it is
        now(() -> {
            cmd("time set 18000");
            cmd("gamerule doDaylightCycle false");
            cmd("kill " + name);
        });
        waitTicks(80);
        expect("the player respawns", () -> player.isAlive() && SelfTestClient.alive()
                && player.distanceToSqr(1007.5, 150, 1007.5) < 4.0, 100,
                () -> "alive=" + player.isAlive() + " clientAlive=" + SelfTestClient.alive() + " atSpawnPoint="
                        + (player.distanceToSqr(1007.5, 150, 1007.5) < 4.0));
        expect("death and respawn reset the heat", () -> heat() == 0f && SelfTestClient.heat() == 0f, 40,
                () -> "server=" + heat() + " client=" + SelfTestClient.heat());
        now(() -> {
            log("HUD after the respawn " + SelfTestClient.hud());
            effects();
        });

        // --- real clicks: a block placed where the click is aimed
        now(() -> {
            aimFloor();
            give("minecraft:cobblestone");
        });
        waitTicks(30);
        click();
        expect("a real click places a block", () -> is(1000, 150, 998, Blocks.COBBLESTONE)
                && SelfTestClient.blockAt(1000, 150, 998).equals("minecraft:cobblestone"), 40,
                () -> "server=" + blockAt(1000, 150, 998) + " client=" + SelfTestClient.blockAt(1000, 150, 998));

        // --- water bucket at 20%: nothing evaporates before 40%
        now(() -> {
            cmd("setblock 1000 150 998 minecraft:air");
            give("minecraft:water_bucket");
        });
        waitTicks(30);
        click();
        expect("water is poured as usual before 40%", () -> is(1000, 150, 998, Blocks.WATER)
                && hand().equals("minecraft:bucket") && SelfTestClient.blockAt(1000, 150, 998).equals("minecraft:water")
                && SelfTestClient.hand().equals("minecraft:bucket"), 40,
                () -> "server=" + blockAt(1000, 150, 998) + "/" + hand() + " client=" + SelfTestClient.blockAt(1000, 150, 998)
                        + "/" + SelfTestClient.hand());

        // --- first ritual: the pattern without its egg does nothing
        now(() -> {
            cmd("fill 990 150 990 1010 151 1010 minecraft:air");
            give("minecraft:air");
            cmd("setblock 1000 150 1000 judgementday:ash_block");
            cmd("setblock 1000 151 1000 minecraft:ancient_debris");
            cmd("setblock 1000 152 1000 minecraft:reinforced_deepslate");
            aimPillar();
        });
        waitTicks(30);
        click();
        expect("the ritual needs the whole pattern", () -> is(1000, 150, 1000, ModBlocks.ASH_BLOCK.get())
                && is(1000, 151, 1000, Blocks.ANCIENT_DEBRIS) && is(1000, 152, 1000, Blocks.REINFORCED_DEEPSLATE)
                && hearts() == 0, 0, () -> "hearts=" + hearts());
        now(() -> cmd("setblock 1000 153 1000 minecraft:dragon_egg"));
        waitTicks(20);
        click();
        waitTicks(40);
        // The heart jumps out of the pattern in a random direction: it may land next to the player, who picks it up
        expect("a click on the pattern does the first ritual", () -> pillarGone() && hearts() + carried() == 1
                && clientPillar().equals("minecraft:air,minecraft:air,minecraft:air,minecraft:air")
                && SelfTestClient.hearts() + SelfTestClient.carried() == 1, 40,
                () -> "patternGone=" + pillarGone() + " hearts=" + (hearts() + carried()) + " client=" + clientPillar()
                        + " clientHearts=" + (SelfTestClient.hearts() + SelfTestClient.carried()));
        shot("4_ritual1");
        // The heart is picked up by walking on it
        now(() -> cmd("tp " + name + " 1000.5 150 1000.5 180 39"));
        expect("the heart is picked up", () -> hand().equals("judgementday:heart_of_oblivion") && carried() == 1
                && hearts() == 0 && SelfTestClient.hand().equals("judgementday:heart_of_oblivion")
                && SelfTestClient.carried() == 1 && SelfTestClient.hearts() == 0, 100,
                () -> "hand=" + hand() + " carried=" + carried() + " heartsLeft=" + hearts() + " clientHand="
                        + SelfTestClient.hand() + " clientCarried=" + SelfTestClient.carried() + " clientHeartsLeft="
                        + SelfTestClient.hearts());

        // --- second ritual: not on stone
        now(() -> aimFloor());
        waitTicks(30);
        click();
        expect("the heart does nothing on stone", () -> hand().equals("judgementday:heart_of_oblivion")
                && ConfigManager.apocalypseEndDay == 200, 0,
                () -> "hand=" + hand() + " end=" + ConfigManager.apocalypseEndDay);
        now(() -> {
            cmd("setblock 1000 149 998 minecraft:grass_block");
            dayBefore = ConfigManager.apocalypseCurrentDay;
        });
        waitTicks(20);
        ask("click 3");
        until("the ritual ends the apocalypse", () -> ConfigManager.apocalypseEndDay == dayBefore, 200);
        expect("the client shows the title of the end", () -> SelfTestClient.title(Phase.PHASE_END).startsWith("same "), 40,
                () -> SelfTestClient.title(Phase.PHASE_END));
        shot("5_ritual2_end");
        now(() -> check("sp: a click with the heart on grass ends the apocalypse", ConfigManager.apocalypseEndDay == dayBefore
                && ConfigManager.lastApocalypseStep == 1 && ConfigManager.apocalypseMaxDays == dayBefore - 1
                && ConfigManager.apocalypseCurrentDay == dayBefore,
                "day=" + ConfigManager.apocalypseCurrentDay + " end=" + ConfigManager.apocalypseEndDay + " max="
                        + ConfigManager.apocalypseMaxDays + " step=" + ConfigManager.lastApocalypseStep));
        expect("the heart is used up", () -> hand().equals("minecraft:air") && carried() == 0 && hearts() == 0
                && SelfTestClient.hand().equals("minecraft:air") && SelfTestClient.carried() == 0
                && SelfTestClient.hearts() == 0, 100,
                () -> "hand=" + hand() + " carried=" + carried() + " heartsLeft=" + hearts() + " clientHand="
                        + SelfTestClient.hand() + " clientCarried=" + SelfTestClient.carried() + " clientHearts="
                        + SelfTestClient.hearts());
        sameState("the client has the end of the apocalypse");
        now(() -> log("HUD after the end " + SelfTestClient.hud()));
        waitTicks(120);
        shot("6_after_end");

        // --- the arena after the clicks and the rituals
        compare("at the arena after the rituals");
        now(() -> log("HEAT at the end of the first world: server " + heatWord(heat()) + ", client "
                + heatWord(SelfTestClient.heat())));
        // The world is left while a phase title is on screen: it is not shown in the next one (1.3.5)
        now(() -> NetworkHandler.sendToAll(new PhaseTitlePacket(Phase.PHASE_END)));
        expect("a title is on screen when leaving the world", () -> SelfTestClient.title(Phase.PHASE_END).startsWith("same "),
                40, () -> SelfTestClient.title(Phase.PHASE_END));
    }

    // ---------------------------------------------------------------- second world

    static void secondWorld() {
        final int day2 = Integer.parseInt(SelfTestCommon.props.getProperty("expectDay2", "45"));
        final int max2 = Integer.parseInt(SelfTestCommon.props.getProperty("expectMax2", "80"));
        final String reset = "day=0 max=50 end=200 damage=60 active=false delayed=false counter=true heatbar=true";
        now(() -> {
            // What the heat was when the player joined, before anything else
            log("HEAT of the player joining the second world: server " + heatWord(heat()));
            rules();
        });
        waitTicks(160);
        now(() -> {
            check("sp: leaving the world resets what the client draws", reset.equals(SelfTestClient.afterQuitState)
                    && SelfTestClient.afterQuitHeat == 0f && "gone".equals(SelfTestClient.afterQuitLevel)
                    && "nothing".equals(SelfTestClient.afterQuitTitle),
                    "client[" + SelfTestClient.afterQuitState + "] heat=" + SelfTestClient.afterQuitHeat + " level="
                            + SelfTestClient.afterQuitLevel + " title=" + SelfTestClient.afterQuitTitle);
            check("sp: the second world has its own config", ConfigManager.apocalypseCurrentDay == day2
                    && ConfigManager.apocalypseMaxDays == max2 && ConfigManager.apocalypseEndDay == 200
                    && ConfigManager.lastApocalypseStep == ConfigManager.UNSET,
                    "day=" + ConfigManager.apocalypseCurrentDay + " max=" + ConfigManager.apocalypseMaxDays + " end="
                            + ConfigManager.apocalypseEndDay + " ritualStep="
                            + (ConfigManager.lastApocalypseStep == ConfigManager.UNSET ? "unset" : ConfigManager.lastApocalypseStep));
            check("sp: second world: apocalypse generator installed",
                    level.getChunkSource().getGenerator() instanceof ApocalypseChunkGenerator, "");
            check("sp: second world: the steps follow its config",
                    (int) field(Generator.class, "daysPerPhase") == Math.max(1, max2 / 5),
                    "daysPerPhase=" + field(Generator.class, "daysPerPhase"));
        });
        sameState("second world: the client has the server's state");
        now(() -> log("HUD in the second world " + SelfTestClient.hud()));
        shot("0_second_world");

        // --- the water of a bucket evaporates (56%), with a real click
        goToArena();
        now(() -> {
            aimFloor();
            give("minecraft:cobblestone");
        });
        waitTicks(30);
        click();
        expect("second world: a real click places a block", () -> is(1000, 150, 998, Blocks.COBBLESTONE), 40,
                () -> "server=" + blockAt(1000, 150, 998));
        now(() -> {
            cmd("setblock 1000 150 998 minecraft:air");
            give("minecraft:water_bucket");
        });
        waitTicks(30);
        click();
        waitTicks(20);
        expect("second world: the water of a bucket evaporates", () -> is(1000, 150, 998, Blocks.AIR)
                && hand().equals("minecraft:bucket") && SelfTestClient.blockAt(1000, 150, 998).equals("minecraft:air")
                && SelfTestClient.hand().equals("minecraft:bucket"), 60,
                () -> "server=" + blockAt(1000, 150, 998) + "/" + hand() + " client=" + SelfTestClient.blockAt(1000, 150, 998)
                        + "/" + SelfTestClient.hand());
        shot("1_bucket");
        compare("at the arena of the second world");
    }

    /** End of a world: the first one is left for the second one, the second one ends the game. */
    static void end(boolean abort) {
        log("RESULT" + (session == 1 && !abort ? "1" : "") + " fails=" + SelfTestNew.fails);
        writeOut();
        SelfTestCommon.server = null;
        if (session == 1 && !abort)
            SelfTestClient.switchWorld(SelfTestCommon.props.getProperty("world2", "sptest2"));
        else
            SelfTestClient.quitGame();
    }

    /**
     * The blocks and the light of the client's chunks around the player against the server's.
     * Called on the server thread while the game is paused and the client thread waits: both
     * worlds stand still. Two lines: the blocks, then the light (how many values differ, how
     * many of them where they can be seen, i.e. not inside a block that hides its own light,
     * by how much and in which blocks).
     */
    static String compareWorlds(ServerLevel sl, Level cl, int pcx, int pcz, int radius) {
        int chunks = 0, missing = 0;
        long blocks = 0, blockDiffs = 0;
        // [sky, block light]
        long[] diffs = new long[2], inside = new long[2], seen = new long[2], brighter = new long[2];
        int[] maxSeen = new int[2];
        long[][] byDelta = new long[2][16];
        java.util.Map<String, Integer> seenIn = new java.util.TreeMap<>();
        List<String> blockSamples = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int minY = sl.getMinBuildHeight(), maxY = sl.getMaxBuildHeight();
        final LightLayer[] layers = { LightLayer.SKY, LightLayer.BLOCK };
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                LevelChunk sc = sl.getChunkSource().getChunkNow(cx, cz);
                if (sc == null)
                    continue;
                LevelChunk cc = cl.getChunkSource().getChunkNow(cx, cz);
                if (cc == null) {
                    missing++;
                    continue;
                }
                chunks++;
                LevelChunkSection[] ss = sc.getSections(), cs = cc.getSections();
                for (int i = 0; i < ss.length; i++) {
                    blocks += 4096;
                    if (ss[i].hasOnlyAir() && cs[i].hasOnlyAir())
                        continue;
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState a = ss[i].getBlockState(x, y, z), b = cs[i].getBlockState(x, y, z);
                                if (a != b) {
                                    blockDiffs++;
                                    if (blockSamples.size() < 6)
                                        blockSamples.add(((cx << 4) + x) + "," + (minY + (i << 4) + y) + ","
                                                + ((cz << 4) + z) + " server=" + a + " client=" + b);
                                }
                            }
                }
                for (int y = minY; y < maxY; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            pos.set((cx << 4) + x, y, (cz << 4) + z);
                            for (int l = 0; l < 2; l++) {
                                int a = sl.getBrightness(layers[l], pos), b = cl.getBrightness(layers[l], pos);
                                if (a == b)
                                    continue;
                                diffs[l]++;
                                BlockState state = sc.getBlockState(pos);
                                if (state.isSolidRender(sl, pos)) {
                                    inside[l]++;
                                    continue;
                                }
                                seen[l]++;
                                int delta = Math.abs(a - b);
                                byDelta[l][delta]++;
                                maxSeen[l] = Math.max(maxSeen[l], delta);
                                if (b > a)
                                    brighter[l]++;
                                seenIn.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(),
                                        1, Integer::sum);
                                if (samples.size() < 8)
                                    samples.add((l == 0 ? "sky " : "block ") + pos.toShortString() + " server=" + a + " client=" + b);
                            }
                        }
            }
        }
        // The first columns with a sky light that differs where it can be seen: what is above, both sides
        StringBuilder columns = new StringBuilder();
        int columnsShown = 0;
        for (String sample : samples) {
            if (!sample.startsWith("sky ") || columnsShown >= 3)
                continue;
            String[] xyz = sample.substring(4, sample.indexOf(" server=")).split(", ");
            int x = Integer.parseInt(xyz[0]), y0 = Integer.parseInt(xyz[1]), z = Integer.parseInt(xyz[2]);
            LevelChunk sc = sl.getChunkSource().getChunkNow(x >> 4, z >> 4);
            LevelChunk cc = cl.getChunkSource().getChunkNow(x >> 4, z >> 4);
            columns.append(" | COLUMN ").append(x).append(',').append(z).append(" lowestSource server=")
                    .append(sc.getSkyLightSources().getLowestSourceY(x & 15, z & 15)).append(" client=")
                    .append(cc.getSkyLightSources().getLowestSourceY(x & 15, z & 15)).append(':');
            for (int y = y0 - 1; y <= y0 + 10; y++) {
                pos.set(x, y, z);
                columns.append(' ').append(y).append('=')
                        .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(sl.getBlockState(pos).getBlock()).getPath())
                        .append('/').append(sl.getBrightness(LightLayer.SKY, pos)).append('/').append(cl.getBrightness(LightLayer.SKY, pos));
            }
            for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
                pos.set(x + d[0], y0, z + d[1]);
                columns.append(" n").append(d[0]).append(d[1]).append('=')
                        .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(sl.getBlockState(pos).getBlock()).getPath())
                        .append('/').append(sl.getBrightness(LightLayer.SKY, pos)).append('/').append(cl.getBrightness(LightLayer.SKY, pos));
            }
            columnsShown++;
        }
        StringBuilder light = new StringBuilder();
        for (int l = 0; l < 2; l++) {
            light.append(l == 0 ? "sky: " : " | blockLight: ").append("differ=").append(diffs[l]).append(" insideSolidBlocks=")
                    .append(inside[l]).append(" seen=").append(seen[l]);
            if (seen[l] > 0) {
                light.append(" (clientBrighter=").append(brighter[l]).append(" max=").append(maxSeen[l]).append(" by");
                for (int d = 1; d < 16; d++)
                    if (byDelta[l][d] > 0)
                        light.append(' ').append(d).append(':').append(byDelta[l][d]);
                light.append(')');
            }
        }
        light.append(" | seenIn=").append(seenIn).append(" samples=").append(samples).append(columns);
        return "chunks=" + chunks + " missing=" + missing + " blocks=" + blocks + (blockSamples.isEmpty() ? "" : " samples=" + blockSamples)
                + " blockDiffs=" + blockDiffs + "\n" + light;
    }
}
