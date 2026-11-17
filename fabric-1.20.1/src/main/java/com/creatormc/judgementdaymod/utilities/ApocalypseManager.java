package com.creatormc.judgementdaymod.utilities;

public class ApocalypseManager {

    public static void startApocalypse() {
        ConfigManager.apocalypseActive = true;
        ConfigManager.save();
    }

    public static void stopApocalypse() {
        ConfigManager.apocalypseActive = false;
        ConfigManager.save();
    }

    public static boolean isApocalypseActive() {
        return ConfigManager.apocalypseActive;
    }
}