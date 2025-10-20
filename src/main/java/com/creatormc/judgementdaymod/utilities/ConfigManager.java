package com.creatormc.judgementdaymod.utilities;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ConfigManager {

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("judgementdaymod-apocalypse.properties");

    // Runtime cache of config values
    public static boolean apocalypseActive = false;
    public static int apocalypseCurrentDay = 0;
    public static int apocalypseMaxDays = 100;
    public static int minDamageHeight = 100; // Minimum height for creature damage
    public static int minWaterEvaporationHeight = 80; // Minimum height for water evaporation

    /**
     * Loads the config file into memory.
     * Should be called at mod startup or when a world is loaded.
     */
    public static void load() {
        Properties props = new Properties();

        if (!Files.exists(CONFIG_PATH)) {
            System.out.println("[JudgementDayMod] Config not found, creating with default values.");
            save(); // create the file with default values
            return;
        }

        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);

            apocalypseActive = Boolean.parseBoolean(
                    props.getProperty("apocalypseActive", String.valueOf(apocalypseActive)));

            apocalypseMaxDays = Integer.parseInt(
                    props.getProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays)));

            apocalypseCurrentDay = Integer.parseInt(
                    props.getProperty("apocalypseCurrentDay", String.valueOf(apocalypseCurrentDay)))
                    - (apocalypseMaxDays / 5); // load first day but start from negative stage (before apocalypse)

            minDamageHeight = Integer.parseInt(
                    props.getProperty("minDamageHeight", String.valueOf(minDamageHeight)));

            minWaterEvaporationHeight = Integer.parseInt(
                    props.getProperty("minWaterEvaporationHeight", String.valueOf(minWaterEvaporationHeight)));

            System.out.println("[JudgementDayMod] Config loaded successfully.");

        } catch (IOException e) {
            System.err.println("[JudgementDayMod] Error reading config: " + e.getMessage());
        }
    }

    /**
     * Saves current in-memory variables to the config file.
     */
    public static void save() {
        Properties props = new Properties();
        // TODO: verify that saving logic is correct given the subtraction in load()
        apocalypseCurrentDay += (apocalypseMaxDays / 5);
        props.setProperty("apocalypseActive", String.valueOf(apocalypseActive));
        props.setProperty("apocalypseCurrentDay", String.valueOf(apocalypseCurrentDay));
        props.setProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays));
        props.setProperty("minDamageHeight", String.valueOf(minDamageHeight));
        props.setProperty("minWaterEvaporationHeight", String.valueOf(minWaterEvaporationHeight));

        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(out, "JudgementDayMod Apocalypse Config");
            }

            System.out.println("[JudgementDayMod] Config saved to " + CONFIG_PATH);

        } catch (IOException e) {
            System.err.println("[JudgementDayMod] Error saving config: " + e.getMessage());
        }
    }
}
