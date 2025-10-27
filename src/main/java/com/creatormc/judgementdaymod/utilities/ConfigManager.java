package com.creatormc.judgementdaymod.utilities;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;

public class ConfigManager {

    // Runtime cache of config values
    public static boolean apocalypseActive = false;
    public static boolean startBeforeApocalypse = true;
    public static int apocalypseCurrentDay = 0;
    public static int apocalypseMaxDays = 50;
    public static int minDamageHeight = 60;
    public static int minWaterEvaporationHeight = 60;
    public static int apocalypseEndDay = 200;

    /**
     * Attempts to locate or create the configuration file in multiple possible
     * locations.
     * Tries the standard Forge config directory first, then falls back to the game
     * directory.
     * If neither exists, it will create the config file in the primary location.
     */
    private static Path resolveConfigPath() {
        String fileName = "judgementdaymod-apocalypse.properties";

        // Try location 1: FMLPaths.CONFIGDIR (Forge standard config directory)
        Path configDir1 = FMLPaths.CONFIGDIR.get();

        // Try location 2: GAMEDIR/config (fallback, in case the Forge path is missing)
        Path configDir2 = FMLPaths.GAMEDIR.get().resolve("config");

        // Check which directory exists and is writable
        if (Files.exists(configDir1) && Files.isDirectory(configDir1)) {
            Path configFile = configDir1.resolve(fileName);
            JudgementDayMod.LOGGER.info("[JudgementDayMod] Using config directory: " + configDir1.toAbsolutePath());
            return configFile;
        }

        if (Files.exists(configDir2) && Files.isDirectory(configDir2)) {
            Path configFile = configDir2.resolve(fileName);
            JudgementDayMod.LOGGER
                    .info("[JudgementDayMod] Using fallback config directory: " + configDir2.toAbsolutePath());
            return configFile;
        }

        // No valid config directory found — create one in the primary location
        JudgementDayMod.LOGGER
                .warn("[JudgementDayMod] No config directory found, creating at: " + configDir1.toAbsolutePath());
        return configDir1.resolve(fileName);
    }

    public static void load() {
        Path configPath = resolveConfigPath();
        Properties props = new Properties();

        if (!Files.exists(configPath)) {
            JudgementDayMod.LOGGER.info("[JudgementDayMod] Config not found, creating with default values.");
            save();
            return;
        }

        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);

            apocalypseActive = Boolean.parseBoolean(
                    props.getProperty("apocalypseActive", String.valueOf(apocalypseActive)));

            apocalypseMaxDays = Integer.parseInt(
                    props.getProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays)));

            apocalypseCurrentDay = Integer.parseInt(
                    props.getProperty("apocalypseCurrentDay", String.valueOf(apocalypseCurrentDay)));

            minDamageHeight = Integer.parseInt(
                    props.getProperty("minDamageHeight", String.valueOf(minDamageHeight)));

            minWaterEvaporationHeight = Integer.parseInt(
                    props.getProperty("minWaterEvaporationHeight", String.valueOf(minWaterEvaporationHeight)));

            startBeforeApocalypse = Boolean.parseBoolean(
                    props.getProperty("startBeforeApocalypse", String.valueOf(startBeforeApocalypse)));

            apocalypseEndDay = Integer.parseInt(
                    props.getProperty("apocalypseEndDay", String.valueOf(apocalypseEndDay)));

            if (startBeforeApocalypse) {
                startBeforeApocalypse = false;
                apocalypseCurrentDay -= (apocalypseMaxDays / 5);
                save();
            }

            JudgementDayMod.LOGGER
                    .info("[JudgementDayMod] Config loaded successfully from: " + configPath.toAbsolutePath());

        } catch (IOException e) {
            JudgementDayMod.LOGGER.error("[JudgementDayMod] Error reading config: " + e.getMessage());
        }
    }

    /**
     * Saves current in-memory variables to the config file.
     */
    public static void save() {
        Path configPath = resolveConfigPath();
        Properties props = new Properties();

        props.setProperty("apocalypseActive", String.valueOf(apocalypseActive));
        props.setProperty("apocalypseCurrentDay", String.valueOf(apocalypseCurrentDay));
        props.setProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays));
        props.setProperty("minDamageHeight", String.valueOf(minDamageHeight));
        props.setProperty("minWaterEvaporationHeight", String.valueOf(minWaterEvaporationHeight));
        props.setProperty("apocalypseEndDay", String.valueOf(apocalypseEndDay));
        props.setProperty("startBeforeApocalypse", String.valueOf(startBeforeApocalypse));

        try {
            Files.createDirectories(configPath.getParent());

            try (OutputStream out = Files.newOutputStream(configPath)) {
                props.store(out, "JudgementDayMod Apocalypse Config");
            }

            JudgementDayMod.LOGGER.info("[JudgementDayMod] Config saved to: " + configPath.toAbsolutePath());

        } catch (IOException e) {
            JudgementDayMod.LOGGER.error("[JudgementDayMod] Error saving config: " + e.getMessage());
            e.printStackTrace();
        }
    }
}