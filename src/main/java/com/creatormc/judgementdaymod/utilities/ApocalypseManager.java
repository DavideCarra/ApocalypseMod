package com.creatormc.judgementdaymod.utilities;

public class ApocalypseManager {
    private static boolean apocalypseActive = ConfigManager.apocalypseActive;

    public static void startApocalypse() {
        apocalypseActive = true;
        ConfigManager.save();
    }

    public static void stopApocalypse() {
        apocalypseActive = false;
        ConfigManager.save();
    }

    public static boolean isApocalypseActive() {
        return apocalypseActive;
    }
}