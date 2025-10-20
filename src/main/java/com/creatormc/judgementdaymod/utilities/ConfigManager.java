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

    // Variabili in RAM (cache del config)
    public static boolean apocalypseActive = false;
    public static int apocalypseCurrentDay = 0;
    public static int apocalypseMaxDays = 100;
    public static int minDamageHeight = 100; // Altezza minima per danno creature
    public static int minWaterEvaporationHeight = 80; // Altezza minima evaporazione acqua

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
                    props.getProperty("apocalypseActive", String.valueOf(apocalypseActive)));

            apocalypseMaxDays = Integer.parseInt(
                    props.getProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays)));

            apocalypseCurrentDay = Integer.parseInt(
                    props.getProperty("apocalypseCurrentDay", String.valueOf(apocalypseCurrentDay)))
                    - (apocalypseMaxDays / 5); // carico il primo giorno ma partendo da una fase negativa quindi ad
                                               // apocalisse non iniziata

            minDamageHeight = Integer.parseInt(
                    props.getProperty("minDamageHeight", String.valueOf(minDamageHeight)));

            minWaterEvaporationHeight = Integer.parseInt(
                    props.getProperty("minWaterEvaporationHeight", String.valueOf(minWaterEvaporationHeight)));

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
        // todo capire se il salvataggio è corretto vista la sottrazione nel load
        props.setProperty("apocalypseActive", String.valueOf(apocalypseActive));
        props.setProperty("apocalypseCurrentDay", String.valueOf(apocalypseCurrentDay));
        props.setProperty("apocalypseMaxDays", String.valueOf(apocalypseMaxDays));
        props.setProperty("minDamageHeight", String.valueOf(minDamageHeight));
        props.setProperty("minWaterEvaporationHeight", String.valueOf(minWaterEvaporationHeight));

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