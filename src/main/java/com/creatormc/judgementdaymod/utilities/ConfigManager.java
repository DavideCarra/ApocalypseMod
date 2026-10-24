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
    public static boolean startBeforeApocalypse = true; // start a stage before apocalypse
    public static int apocalypseCurrentDay = 0;
    public static int apocalypseMaxDays = 50;
    public static int minDamageHeight = 60; // Minimum height for creature damage
    public static int minWaterEvaporationHeight = 60; // Minimum height for water evaporation
    public static int apocalypseEndDay = 200;
    // Days of normal play left before the apocalypse starts counting (the day stays put)
    public static int startDelayDays = 0;
    // HUD the players may show (each player can still hide them, see ClientConfig)
    public static boolean showDayCounter = true;
    public static boolean showHeatBar = true;

    // Marks a value missing from the file (configs written by older versions)
    public static final int UNSET = Integer.MIN_VALUE;
    // Last phase whose effects (heights, title, music) were applied
    public static int lastAppliedPhase = UNSET;
    // Destruction step the world stays at when the ritual ends the apocalypse (see Generator)
    public static int lastApocalypseStep = UNSET;
    // Day this version first ran a world already past apocalypseMaxDays (see Generator)
    public static int stepOriginDay = UNSET;
    // The file was written by an older version of the mod
    public static boolean legacyConfig = false;

    private static final String FILE_NAME = "judgementdaymod-apocalypse.properties";
    // Settings new worlds start with (never written by the mod once created)
    private static final String DEFAULTS_FILE_NAME = "judgementdaymod-defaults.properties";
    // Chunk data of the mod in a world: the mod already ran there
    private static final String CHUNK_DATA_FILE = "judgementday_chunkdata.dat";

    // The config of the world being played (in its serverconfig folder)
    private static Path worldConfigPath;

    /**
     * The config directory: the standard Forge one, or GAMEDIR/config if it is missing.
     */
    private static Path configDir() {
        // Try location 1: FMLPaths.CONFIGDIR (Forge standard config directory)
        Path configDir1 = FMLPaths.CONFIGDIR.get();
        if (Files.isDirectory(configDir1))
            return configDir1;
        // Try location 2: GAMEDIR/config (fallback, in case the Forge path is missing)
        Path configDir2 = FMLPaths.GAMEDIR.get().resolve("config");
        if (Files.isDirectory(configDir2)) {
            JudgementDayMod.LOGGER.info("[JudgementDayMod] Using fallback config directory: " + configDir2.toAbsolutePath());
            return configDir2;
        }
        // No valid config directory found — create one in the primary location
        return configDir1;
    }

    /**
     * Loads the config of the world in worldDir (each world has its own, in its
     * serverconfig folder, same format as before). A world without one gets it here:
     * a world the mod already ran in takes the single config older versions shared by
     * all worlds (config/judgementdaymod-apocalypse.properties), so it carries on where
     * it was; a new world starts on day 0 with the settings of
     * config/judgementdaymod-defaults.properties (created with the default values).
     */
    public static void load(Path worldDir) {
        worldConfigPath = worldDir.resolve("serverconfig").resolve(FILE_NAME);
        resetToDefaults();

        if (Files.exists(worldConfigPath)) {
            read(worldConfigPath, true);
        } else {
            final Path oldShared = configDir().resolve(FILE_NAME);
            final boolean modRanHere = Files.exists(worldDir.resolve("data").resolve(CHUNK_DATA_FILE));
            if (modRanHere && Files.exists(oldShared)) {
                JudgementDayMod.LOGGER.info("[JudgementDayMod] World config created from " + oldShared.toAbsolutePath());
                read(oldShared, true);
            } else {
                final Path defaults = configDir().resolve(DEFAULTS_FILE_NAME);
                if (!Files.exists(defaults)) {
                    saveDefaults(defaults);
                }
                JudgementDayMod.LOGGER.info("[JudgementDayMod] New world config from " + defaults.toAbsolutePath());
                read(defaults, false);
                legacyConfig = false;
            }
            save();
        }

        if (startBeforeApocalypse) {
            startBeforeApocalypse = false;
            apocalypseCurrentDay -= (apocalypseMaxDays / 5);
            save();
        }
        JudgementDayMod.LOGGER.info("[JudgementDayMod] Config loaded from: " + worldConfigPath.toAbsolutePath());
    }

    /** The values of a new config. */
    private static void resetToDefaults() {
        apocalypseActive = false;
        startBeforeApocalypse = true;
        apocalypseCurrentDay = 0;
        apocalypseMaxDays = 50;
        minDamageHeight = 60;
        minWaterEvaporationHeight = 60;
        apocalypseEndDay = 200;
        startDelayDays = 0;
        showDayCounter = true;
        showHeatBar = true;
        lastAppliedPhase = UNSET;
        lastApocalypseStep = UNSET;
        stepOriginDay = UNSET;
        legacyConfig = false;
    }

    /**
     * Reads a config file over the current values.
     *
     * @param state false for the defaults file: only the settings, not the state of a world
     */
    private static void read(Path path, boolean state) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            JudgementDayMod.LOGGER.error("[JudgementDayMod] Error reading config: " + e.getMessage());
            return;
        }

        apocalypseMaxDays = parseInt(props, "apocalypseMaxDays", apocalypseMaxDays);
        minDamageHeight = parseInt(props, "minDamageHeight", minDamageHeight);
        minWaterEvaporationHeight = parseInt(props, "minWaterEvaporationHeight", minWaterEvaporationHeight);
        startBeforeApocalypse = parseBoolean(props, "startBeforeApocalypse", startBeforeApocalypse);
        apocalypseEndDay = parseInt(props, "apocalypseEndDay", apocalypseEndDay);
        startDelayDays = Math.max(0, parseInt(props, "startDelayDays", startDelayDays));
        showDayCounter = parseBoolean(props, "showDayCounter", showDayCounter);
        showHeatBar = parseBoolean(props, "showHeatBar", showHeatBar);
        if (!state)
            return;

        apocalypseActive = parseBoolean(props, "apocalypseActive", apocalypseActive);
        apocalypseCurrentDay = parseInt(props, "apocalypseCurrentDay", apocalypseCurrentDay);

        legacyConfig = !props.containsKey("lastAppliedPhase");

        lastAppliedPhase = parseOrUnset(props, "lastAppliedPhase");
        stepOriginDay = parseOrUnset(props, "stepOriginDay");
        lastApocalypseStep = parseOrUnset(props, "lastApocalypseStep");
    }

    /** A setting: missing or unreadable keeps the current value. */
    private static int parseInt(Properties props, String key, int current) {
        String value = props.getProperty(key);
        if (value == null)
            return current;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            JudgementDayMod.LOGGER.warn("[JudgementDayMod] Ignoring invalid " + key + "=" + value);
            return current;
        }
    }

    private static boolean parseBoolean(Properties props, String key, boolean current) {
        String value = props.getProperty(key);
        return value == null ? current : Boolean.parseBoolean(value.trim());
    }

    /**
     * A value written by the mod itself: missing or unreadable (edited by hand) means
     * unknown, it is then worked out again.
     */
    private static int parseOrUnset(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null)
            return UNSET;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            JudgementDayMod.LOGGER.warn("[JudgementDayMod] Ignoring invalid " + key + "=" + value);
            return UNSET;
        }
    }

    /**
     * Saves current in-memory variables to the config file of the world.
     */
    public static void save() {
        if (worldConfigPath == null)
            return;
        Properties props = new Properties();

        props.setProperty("apocalypseActive", String.valueOf(apocalypseActive));
        props.setProperty("apocalypseCurrentDay", String.valueOf(apocalypseCurrentDay));
        props.setProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays));
        props.setProperty("minDamageHeight", String.valueOf(minDamageHeight));
        props.setProperty("minWaterEvaporationHeight", String.valueOf(minWaterEvaporationHeight));
        props.setProperty("apocalypseEndDay", String.valueOf(apocalypseEndDay));
        props.setProperty("startBeforeApocalypse", String.valueOf(startBeforeApocalypse));
        props.setProperty("startDelayDays", String.valueOf(startDelayDays));
        props.setProperty("showDayCounter", String.valueOf(showDayCounter));
        props.setProperty("showHeatBar", String.valueOf(showHeatBar));
        if (lastAppliedPhase != UNSET) {
            props.setProperty("lastAppliedPhase", String.valueOf(lastAppliedPhase));
        }
        if (lastApocalypseStep != UNSET) {
            props.setProperty("lastApocalypseStep", String.valueOf(lastApocalypseStep));
        }
        if (stepOriginDay != UNSET) {
            props.setProperty("stepOriginDay", String.valueOf(stepOriginDay));
        }
        store(worldConfigPath, props, "JudgementDayMod Apocalypse Config (this world)");
    }

    /** Writes the defaults file new worlds start from, with the default values. */
    private static void saveDefaults(Path path) {
        Properties props = new Properties();
        props.setProperty("apocalypseMaxDays", "50");
        props.setProperty("apocalypseEndDay", "200");
        props.setProperty("minDamageHeight", "60");
        props.setProperty("minWaterEvaporationHeight", "60");
        props.setProperty("startBeforeApocalypse", "true");
        props.setProperty("startDelayDays", "0");
        props.setProperty("showDayCounter", "true");
        props.setProperty("showHeatBar", "true");
        store(path, props, "JudgementDayMod settings for new worlds (each world has its own config in <world>/serverconfig)");
    }

    private static void store(Path path, Properties props, String comment) {
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, comment);
            }
            JudgementDayMod.LOGGER.info("[JudgementDayMod] Config saved to: " + path.toAbsolutePath());
        } catch (IOException e) {
            JudgementDayMod.LOGGER.error("[JudgementDayMod] Error saving config: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
