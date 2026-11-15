package com.creatormc.judgementdaymod.models;

/**
 * The apocalypse state the client draws (HUD, sky, sun), as the server last sent it
 * (ApocalypseSyncPacket): on a dedicated server the client has no state of its own.
 * Until the first sync, the values of a new config.
 */
public class ClientApocalypseData {

    public static int currentDay;
    public static int maxDays;
    public static int endDay;
    public static int damageHeight;
    public static boolean active;
    public static boolean delayed;
    public static boolean showDayCounter;
    public static boolean showHeatBar;

    static {
        reset();
    }

    public static void set(int currentDay, int maxDays, int endDay, int damageHeight, boolean active,
            boolean delayed, boolean showDayCounter, boolean showHeatBar) {
        ClientApocalypseData.currentDay = currentDay;
        ClientApocalypseData.maxDays = maxDays;
        ClientApocalypseData.endDay = endDay;
        ClientApocalypseData.damageHeight = damageHeight;
        ClientApocalypseData.active = active;
        ClientApocalypseData.delayed = delayed;
        ClientApocalypseData.showDayCounter = showDayCounter;
        ClientApocalypseData.showHeatBar = showHeatBar;
    }

    /** Back to the values of a new config (the client left the world). */
    public static void reset() {
        set(0, 50, 200, 60, false, false, true, true);
    }
}
