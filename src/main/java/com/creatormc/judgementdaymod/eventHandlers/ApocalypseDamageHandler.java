package com.creatormc.judgementdaymod.eventHandlers;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID)
public class ApocalypseDamageHandler {

    private static int tickCounter = 0;
    private static final int DAMAGE_INTERVAL = 40; // Danno ogni 2 secondi (40 ticks)

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        // Esegui solo sul server e nella fase END
        if (event.side.isClient() || event.phase != TickEvent.Phase.END) {
            return;
        }

        Level level = event.level;

        // Solo per l'Overworld
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }

        tickCounter++;
        if (tickCounter < DAMAGE_INTERVAL) {
            return;
        }
        tickCounter = 0;


        // Calcola il danno in base allo stage
        double percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

        // Sotto il 20% nessun danno
        if (percent < 20.0) {
            return;
        }

        // Normalizza tra 20% e 100%
        double factor = (percent - 20.0) / 80.0;

        // Danno incrementale: da 0.5 a 4.0 cuori
        float baseDamage = (float) (0.5 + 3.5 * factor);

        // Applica danno a tutte le entità viventi
        AABB searchBox = new AABB(
                -30_000_000, ConfigManager.minDamageHeight, -30_000_000,
                30_000_000, 320, 30_000_000);

        for (LivingEntity livingEntity : level.getEntitiesOfClass(LivingEntity.class, searchBox)) {

            // Salta i giocatori in creative/spectator
            if (livingEntity instanceof Player player) {
                if (player.isCreative() || player.isSpectator()) {
                    continue;
                }
            }

            // Controlla se l'entità è sopra l'altezza minima
            if (livingEntity.getY() < ConfigManager.minDamageHeight) {
                continue;
            }

            // Controlla protezione in base alla percentuale
            boolean isUnderCover = !level.canSeeSky(livingEntity.blockPosition());
            boolean isNight = !level.isDay();

            // Fino al 40%: protetti se al coperto
            if (percent <= 40.0 && isUnderCover) {
                continue;
            }

            // Fino al 40%: danno solo di giorno
            if (percent <= 40.0 && isNight) {
                continue;
            }

            // Calcola il danno base
            float damage = baseDamage;

            // Tra 40% e 60%: di notte danno dimezzato
            if (percent > 40.0 && percent <= 60.0 && isNight) {
                damage *= 0.5F;
            }

            // Sopra il 60%: danno uguale giorno e notte (nessuna riduzione)

            // Applica modificatori aggiuntivi
            damage = calculateDamage(damage, livingEntity, factor);

            DamageSource damageSource = level.damageSources().magic(); // O crea un custom damage source
            livingEntity.hurt(damageSource, damage);

            // Opzionale: aggiungi effetto fuoco nelle fasi avanzate
            if (percent > 70.0) {
                int fireDuration = (int) (40 * factor); // Fino a 2 secondi di fuoco
                livingEntity.setSecondsOnFire(fireDuration);
            }
        }
    }

    /**
     * Calcola il danno finale considerando armature e resistenze
     */
    private static float calculateDamage(float baseDamage, LivingEntity entity, double factor) {
        // Danno base
        float damage = baseDamage;

        // Opzionale: riduci danno in acqua
        if (entity.isInWater()) {
            damage *= 0.5F;
        }

        // Opzionale: aumenta danno se l'entità è già in fiamme
        if (entity.isOnFire()) {
            damage *= 1.2F;
        }

        // Nelle fasi finali (>90%), danno ancora maggiore
        if (factor > 0.875) { // >90%
            damage *= 1.5F;
        }

        return damage;
    }
}