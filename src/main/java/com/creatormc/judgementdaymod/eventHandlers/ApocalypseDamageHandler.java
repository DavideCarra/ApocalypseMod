package com.creatormc.judgementdaymod.eventHandlers;

import com.creatormc.judgementdaymod.models.HeatSyncPacket;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.creatormc.judgementdaymod.utilities.ConfigManager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID)
public class ApocalypseDamageHandler {

    private static int tickCounter = 0;
    private static final int TICK_INTERVAL = 20; // 1 second

    // Map UUID → heat level (0–10)
    private static final Map<UUID, Float> heatLevels = new HashMap<>();

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.side.isClient() || event.phase != TickEvent.Phase.END)
            return;

        Level level = event.level;
        if (!level.dimension().equals(Level.OVERWORLD))
            return;

        tickCounter++;
        if (tickCounter < TICK_INTERVAL)
            return;
        tickCounter = 0;

        double percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

        if (!(level instanceof ServerLevel serverLevel))
            return;

        // Build unified list of living entities + players without duplicates
        Set<UUID> seen = new HashSet<>();
        List<LivingEntity> allLiving = new ArrayList<>();

        AABB searchBox = new AABB(
                -30_000_000, ConfigManager.minDamageHeight, -30_000_000,
                30_000_000, 320, 30_000_000);

        for (LivingEntity e : serverLevel.getEntitiesOfClass(LivingEntity.class, searchBox))
            if (seen.add(e.getUUID()))
                allLiving.add(e);

        for (ServerPlayer p : serverLevel.players())
            if (seen.add(p.getUUID()))
                allLiving.add(p);

        // Loop through all living entities
        for (LivingEntity entity : allLiving) {

            if (entity instanceof Player p && (p.isCreative() || p.isSpectator()))
                continue;

            UUID id = entity.getUUID();
            float heat = heatLevels.getOrDefault(id, 0f);

            boolean isUnderCover = !level.canSeeSky(entity.blockPosition());
            boolean isInWater = entity.isInWater();
            boolean isInRain = level.isRainingAt(entity.blockPosition());
            boolean isInBubble = entity.getBlockStateOn().is(Blocks.BUBBLE_COLUMN);
            boolean isInCooling = isInWater || isInRain || isInBubble;
            double y = entity.getY();

            boolean isNight = !level.isDay();

            // === HEAT MANAGEMENT ===
            if (percent < 0)
                continue;

            // Normalize [0..1]
            float pctNorm = (float) Math.max(0, Math.min(1.0, percent / 100.0));

            if (percent < 20.0) {
                // Initial phase: no effect at night
                if (isNight)
                    continue;

                float growth = 0.25f;

                if (isUnderCover)
                    heat -= 0.15f;
                if (isInCooling)
                    heat -= 0.4f;
                if (!isUnderCover && !isInCooling)
                    heat += growth;
            } else {
                if (y >= ConfigManager.minDamageHeight) {

                    float growth = (float) (0.25f + 0.75f * pctNorm);

                    // At night between 20–60% → halve effect
                    if (isNight && percent <= 60.0)
                        growth *= 0.5f;

                    if (percent >= 40.0) {
                        if (isUnderCover)
                            growth *= 0.9f;
                        if (isInCooling)
                            growth *= 0.7f;
                    } else {
                        if (isUnderCover)
                            heat -= 0.2f;
                        if (isInCooling)
                            heat -= 0.5f;
                    }

                    heat += growth;

                } else {
                    if (isUnderCover)
                        heat -= 0.3f;
                    if (isInCooling)
                        heat -= 1.0f;
                }
            }

            // Clamp 0–10
            heat = Math.max(0f, Math.min(10f, heat));

            // === APPLY FIRE EFFECT AND DAMAGE ===
            if (heat >= 10f) {
                entity.setSecondsOnFire(8);
                DamageSource fireSource = level.damageSources().inFire();
                float damage = 2.0F;

                // At night between 20–60% → half damage
                if (isNight && percent > 20.0 && percent <= 60.0)
                    damage *= 0.5f;

                entity.hurt(fireSource, damage);
            } else if (heat >= 7f) {
                entity.setSecondsOnFire(3);
            }

            // Save updated value
            heatLevels.put(id, heat);

            // Send packet to client
            if (entity instanceof ServerPlayer serverPlayer) {
                NetworkHandler.sendToClient(new HeatSyncPacket(heat), serverPlayer);
            }
        }

        // Cleanup map to avoid memory accumulation
        heatLevels.keySet().removeIf(entryId -> {
            var e = serverLevel.getEntity(entryId);
            return e == null || e.isRemoved();
        });
    }
}
