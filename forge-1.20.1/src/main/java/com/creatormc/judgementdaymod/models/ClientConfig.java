package com.creatormc.judgementdaymod.models;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Settings of this player's client (config/judgementdaymod-client.properties): the HUD
 * they want to see, among the ones the server lets them show.
 */
public class ClientConfig {

    private static final String FILE_NAME = "judgementdaymod-client.properties";

    public static boolean showDayCounter = true;
    public static boolean showHeatBar = true;
    private static boolean loaded = false;

    /** Read once, the first time the HUD is drawn; created with the defaults if missing. */
    public static void ensureLoaded() {
        if (loaded)
            return;
        loaded = true;
        final Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        final Properties props = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                JudgementDayMod.LOGGER.error("[JudgementDayMod] Error reading client config: " + e.getMessage());
            }
            showDayCounter = Boolean.parseBoolean(props.getProperty("showDayCounter", "true").trim());
            showHeatBar = Boolean.parseBoolean(props.getProperty("showHeatBar", "true").trim());
            return;
        }
        props.setProperty("showDayCounter", "true");
        props.setProperty("showHeatBar", "true");
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "JudgementDayMod HUD shown on this client (if the server allows it)");
            }
        } catch (IOException e) {
            JudgementDayMod.LOGGER.error("[JudgementDayMod] Error saving client config: " + e.getMessage());
        }
    }

    public static boolean dayCounterShown() {
        ensureLoaded();
        return showDayCounter && ClientApocalypseData.showDayCounter && !ClientApocalypseData.delayed;
    }

    public static boolean heatBarShown() {
        ensureLoaded();
        return showHeatBar && ClientApocalypseData.showHeatBar;
    }
}
