package com.creatormc.judgementdaymod.selftest;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.creatormc.judgementdaymod.handlers.PhaseTitleOverlay;
import com.creatormc.judgementdaymod.models.ClientApocalypseData;
import com.creatormc.judgementdaymod.models.ClientConfig;
import com.creatormc.judgementdaymod.models.ClientHeatData;
import com.creatormc.judgementdaymod.setup.ModItems;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.creatormc.judgementdaymod.utilities.ConfigManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

/**
 * Client side of the single player scenario (not part of the mod, see SelfTestSingle): what
 * the real client of the game has and draws, the pause, leaving a world for another one.
 * Only loaded by that scenario, in a client.
 */
public class SelfTestClient {

    /** What happened while the game was paused. */
    static class PauseResult {
        boolean paused;
        // Changes on the server between the start and the end of the pause (all 0 if nothing ran)
        int ticks;
        long dayTime;
        int day;
        float heat;
        // The client's light queues were empty when the worlds were compared
        boolean lightQuiet;
        String compare;
        String error;
    }

    static boolean registered;
    // The client thread is inside one of the calls below (the game ticks while it saves or loads a world)
    static boolean busy;

    static volatile PauseResult pauseResult;
    static volatile ServerLevel pauseLevel;
    static volatile int pauseRadius;
    static int pauseStage;
    static int pauseTicks;
    static int[] before;
    static long beforeTime;
    static float beforeHeat;

    static volatile String worldToOpen;
    static int switchStage;
    static int switchTicks;
    static volatile String afterQuitState;
    static volatile float afterQuitHeat;
    static volatile String afterQuitLevel;
    static volatile String afterQuitTitle;

    static synchronized void register() {
        if (registered)
            return;
        registered = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> onClientTick());
    }

    // ---------------------------------------------------------------- what the client has

    /** The apocalypse state the client draws, written as SelfTestSingle.serverState does. */
    static String state() {
        return "day=" + ClientApocalypseData.currentDay + " max=" + ClientApocalypseData.maxDays + " end="
                + ClientApocalypseData.endDay + " damage=" + ClientApocalypseData.damageHeight + " active="
                + ClientApocalypseData.active + " delayed=" + ClientApocalypseData.delayed + " counter="
                + ClientApocalypseData.showDayCounter + " heatbar=" + ClientApocalypseData.showHeatBar;
    }

    static float heat() {
        return ClientHeatData.get();
    }

    /** What the HUD of the mod shows now (the conditions of the two overlays). */
    static String hud() throws Exception {
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null)
                return "no world";
            int cur = ClientApocalypseData.currentDay, max = ClientApocalypseData.maxDays;
            boolean counter = ClientConfig.dayCounterShown() && cur < max;
            boolean bar = ClientConfig.heatBarShown() && ClientHeatData.get() > 0f && !mc.player.isCreative()
                    && !mc.player.isSpectator() && mc.player.level().dimension().equals(Level.OVERWORLD) && cur >= 0
                    && cur < ClientApocalypseData.endDay;
            return "dayCounter=" + (counter ? "'Days left: " + Math.abs(max - cur) + "'" : "hidden") + " heatBar="
                    + (bar ? "shown" : "hidden");
        });
    }

    /** "same ..." if the client shows the title of the phase now. */
    static String title(Phase phase) throws Exception {
        Field f = PhaseTitleOverlay.class.getDeclaredField("currentTitle");
        f.setAccessible(true);
        Component shown = (Component) f.get(null);
        f = PhaseTitleOverlay.class.getDeclaredField("currentSubtitle");
        f.setAccessible(true);
        Component sub = (Component) f.get(null);
        String want = phase.getTitleComponent().getString() + " / " + phase.getDescriptionComponent().getString();
        String got = shown == null ? "nothing" : shown.getString() + " / " + (sub == null ? "" : sub.getString());
        boolean same = shown != null && sub != null && shown.equals(phase.getTitleComponent())
                && sub.equals(phase.getDescriptionComponent());
        return same ? "same '" + got + "'" : "different: shown '" + got + "' expected '" + want + "'";
    }

    /** The title of a phase the client has on screen ("nothing" if none). */
    static String shownTitle() {
        try {
            Field f = PhaseTitleOverlay.class.getDeclaredField("currentTitle");
            f.setAccessible(true);
            Component shown = (Component) f.get(null);
            return shown == null ? "nothing" : shown.getString();
        } catch (ReflectiveOperationException e) {
            return e.toString();
        }
    }

    static boolean alive() throws Exception {
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && mc.player.isAlive() && mc.screen == null;
        });
    }

    /** The block the client has there. */
    static String blockAt(int x, int y, int z) throws Exception {
        return onClient(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? "no world"
                    : BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
        });
    }

    /** The item the client has in the player's hand. */
    static String hand() throws Exception {
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc.player == null ? "no player"
                    : BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString();
        });
    }

    /** The hearts of oblivion lying in the client's world. */
    static int hearts() throws Exception {
        return onClient(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            int n = 0;
            if (level != null)
                for (Entity e : level.entitiesForRendering())
                    if (e instanceof ItemEntity item && item.getItem().is(ModItems.HEART_OF_OBLIVION.get()))
                        n++;
            return n;
        });
    }

    /** The hearts of oblivion the client has in the player's inventory. */
    static int carried() throws Exception {
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc.player == null ? -1 : mc.player.getInventory().countItem(ModItems.HEART_OF_OBLIVION.get());
        });
    }

    /** Runs on the client thread and waits for it (called from the server thread). */
    static <T> T onClient(Supplier<T> s) throws Exception {
        return Minecraft.getInstance().submit(s).get(30, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- pause

    /** Asks the client to pause the game and compare its world with the server's (see pauseResult). */
    static void pauseAndCompare(ServerLevel level, int radius) {
        pauseResult = null;
        pauseRadius = radius;
        pauseLevel = level;
    }

    static boolean serverPaused(IntegratedServer server) throws Exception {
        Field f = IntegratedServer.class.getDeclaredField("paused");
        f.setAccessible(true);
        return f.getBoolean(server);
    }

    static boolean clientLightQuiet(ClientLevel level) throws Exception {
        Field f = ClientLevel.class.getDeclaredField("lightUpdateQueue");
        f.setAccessible(true);
        return ((Deque<?>) f.get(level)).isEmpty() && !level.getChunkSource().getLightEngine().hasLightWork();
    }

    static void onClientTick() {
        if (busy)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (pauseLevel != null && pauseResult == null) {
            try {
                pauseTick(mc);
            } catch (Throwable e) {
                PauseResult r = new PauseResult();
                r.error = e.toString();
                finishPause(mc, r);
            }
        } else if (worldToOpen != null) {
            switchTick(mc);
        }
    }

    static void finishPause(Minecraft mc, PauseResult r) {
        if (mc.screen instanceof PauseScreen)
            mc.setScreen(null);
        pauseStage = 0;
        pauseLevel = null;
        pauseResult = r;
    }

    static void pauseTick(Minecraft mc) throws Exception {
        IntegratedServer server = mc.getSingleplayerServer();
        pauseTicks++;
        if (pauseStage == 0) {
            if (mc.player == null || mc.level == null || server == null)
                throw new IllegalStateException("no single player world");
            if (mc.screen != null)
                throw new IllegalStateException("a screen is open: " + mc.screen.getClass().getName());
            // What the Esc key does
            mc.pauseGame(false);
            pauseStage = 1;
            pauseTicks = 0;
        } else if (pauseStage == 1) {
            // The server sees the pause at its next tick, and saves the world
            if (!mc.isPaused() || !serverPaused(server)) {
                if (pauseTicks > 600)
                    throw new IllegalStateException("the game did not pause: client=" + mc.isPaused() + " server="
                            + serverPaused(server) + " screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getName()));
                return;
            }
            before = new int[] { server.getTickCount(), ConfigManager.apocalypseCurrentDay };
            beforeTime = server.overworld().getDayTime();
            Float h = SelfTestSingle.heatOf(mc.player.getUUID());
            beforeHeat = h == null ? -1f : h;
            pauseStage = 2;
            pauseTicks = 0;
        } else {
            // Three seconds of pause at least, and until the client has applied all the light it was sent
            final boolean lightQuiet = clientLightQuiet(mc.level)
                    && !pauseLevel.getChunkSource().getLightEngine().hasLightWork();
            if (pauseTicks < 60 || (!lightQuiet && pauseTicks < 1200))
                return;
            PauseResult r = new PauseResult();
            r.paused = mc.isPaused() && serverPaused(server);
            r.ticks = server.getTickCount() - before[0];
            r.day = ConfigManager.apocalypseCurrentDay - before[1];
            r.dayTime = server.overworld().getDayTime() - beforeTime;
            Float h = SelfTestSingle.heatOf(mc.player.getUUID());
            r.heat = (h == null ? -1f : h) - beforeHeat;
            r.lightQuiet = lightQuiet;
            // Both worlds stand still: the server thread compares them while this thread waits
            final ServerLevel sl = pauseLevel;
            final ClientLevel cl = mc.level;
            final int cx = mc.player.chunkPosition().x, cz = mc.player.chunkPosition().z, radius = pauseRadius;
            r.compare = server.submit(() -> SelfTestSingle.compareWorlds(sl, cl, cx, cz, radius)).get(300, TimeUnit.SECONDS);
            finishPause(mc, r);
        }
    }

    // ---------------------------------------------------------------- another world

    /** Leaves the world as the Save and Quit to Title button does, then opens another one. */
    static void switchWorld(String world) {
        switchStage = 0;
        worldToOpen = world;
    }

    static void switchTick(Minecraft mc) {
        switchTicks++;
        if (switchStage == 0) {
            if (mc.level == null)
                return;
            busy = true;
            try {
                // PauseScreen.onDisconnect
                mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
                mc.setScreen(new TitleScreen());
            } finally {
                busy = false;
            }
            afterQuitState = state();
            afterQuitHeat = heat();
            afterQuitLevel = mc.level == null && mc.player == null ? "gone" : "still there";
            afterQuitTitle = shownTitle();
            switchStage = 1;
            switchTicks = 0;
        } else if (switchTicks >= 40) {
            // Still as it was left two seconds later, on the title screen
            if (!state().equals(afterQuitState) || heat() != afterQuitHeat)
                afterQuitState = "changed on the title screen: " + state() + " heat=" + heat();
            final String world = worldToOpen;
            worldToOpen = null;
            busy = true;
            try {
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(), world);
            } finally {
                busy = false;
            }
        }
    }

    /** Ends the game as the Quit Game button does. */
    static void quitGame() {
        Minecraft.getInstance().stop();
    }
}
