package com.creatormc.judgementdaymod.utilities;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ConfigManager {

    private static final Path CONFIG_PATH =
            FMLPaths.CONFIGDIR.get().resolve("judgementdaymod-apocalypse.properties");

    // Variabili in RAM (cache del config)
    public static boolean apocalypseActive = false;
    public static int apocalypseStage = 0;
    public static int apocalypseMaxDays = 100; // <-- aggiunto

    /**
     * Carica il config da file in memoria.
     * Va chiamato all'avvio della mod o all'apertura del mondo.
     */
    public static void load() {
        Properties props = new Properties();

        if (!Files.exists(CONFIG_PATH)) {
            System.out.println("[JudgementDayMod] Config non trovato, creo con valori default.");
            save(); // crea il file con i valori di default
            return;
        }

        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);

            apocalypseActive = Boolean.parseBoolean(
                    props.getProperty("apocalypseEnabled", String.valueOf(apocalypseActive)));

            apocalypseStage = Integer.parseInt(
                    props.getProperty("apocalypseStage", String.valueOf(apocalypseStage)));

            apocalypseMaxDays = Integer.parseInt( // <-- aggiunto
                    props.getProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays)));

            System.out.println("[JudgementDayMod] Config caricato con successo.");

        } catch (IOException e) {
            System.err.println("[JudgementDayMod] Errore nel leggere il config: " + e.getMessage());
        }
    }

    /**
     * Salva le variabili correnti in memoria nel file config.
     */
    public static void save() {
        Properties props = new Properties();

        props.setProperty("apocalypseEnabled", String.valueOf(apocalypseActive));
        props.setProperty("apocalypseStage", String.valueOf(apocalypseStage));
        props.setProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays)); // <-- aggiunto

        try {
            // Assicura che la cartella config esista
            Files.createDirectories(CONFIG_PATH.getParent());

            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(out, "JudgementDayMod Apocalypse Config");
            }

            System.out.println("[JudgementDayMod] Config salvato in " + CONFIG_PATH);

        } catch (IOException e) {
            System.err.println("[JudgementDayMod] Errore nel salvare il config: " + e.getMessage());
        }
    }
}
