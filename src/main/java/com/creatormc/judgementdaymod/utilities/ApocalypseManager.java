package com.creatormc.judgementdaymod.utilities;

public class ApocalypseManager {
    private static boolean apocalypseActive = ConfigManager.apocalypseActive;



    public static void startApocalypse() {
        apocalypseActive = true;
    }

    public static void stopApocalypse() {
        apocalypseActive = false;
    }

    public static boolean isApocalypseActive() {
        return apocalypseActive;
    }
}