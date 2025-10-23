package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WaterEvaporateHandler {

    @SubscribeEvent
    public static void onPlayerUseBucket(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        Player player = event.getEntity();

        // Server-side only
        if (level.isClientSide)
            return;

        // If apocalypse is over, do not evaporate water in buckets
        if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseEndDay) {
            return;
        }

        // Check if it’s any bucket containing water (including fish)
        Item item = player.getItemInHand(event.getHand()).getItem();
        if (item != Items.WATER_BUCKET &&
                item != Items.PUFFERFISH_BUCKET &&
                item != Items.SALMON_BUCKET &&
                item != Items.COD_BUCKET &&
                item != Items.TROPICAL_FISH_BUCKET &&
                item != Items.AXOLOTL_BUCKET &&
                item != Items.TADPOLE_BUCKET &&
                item != Items.POWDER_SNOW_BUCKET) {
            return;
        }

        // Calculate apocalypse percentage
        double percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

        // Evaporation active from 40%
        if (percent < 40.0) {
            return; // Allow normal placement
        }

        // Position where water would be placed
        BlockPos pos = event.getHitVec().getBlockPos().relative(event.getFace());

        // Check if it’s below minimum height
        if (pos.getY() < ConfigManager.minWaterEvaporationHeight) {
            return; // Allow placement below minimum height
        }

        // Evaporate water
        level.levelEvent(2001, pos, Block.getId(Blocks.WATER.defaultBlockState()));
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH,
                SoundSource.BLOCKS, 0.5F, 2.6F + (level.random.nextFloat() - level.random.nextFloat()) * 0.8F);

        player.setItemInHand(event.getHand(), Items.BUCKET.getDefaultInstance());

        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }
}
