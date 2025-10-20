package com.creatormc.judgementdaymod.eventHandlers;

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

        // Solo lato server
        if (level.isClientSide)
            return;
        // Controlla se è un qualsiasi secchio contenente acqua (inclusi pesci)
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

        // Calcola percentuale apocalisse
        double percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

        // Evaporazione attiva dal 40%
        if (percent < 40.0) {
            return; // Permetti piazzamento normale
        }

        // Posizione dove verrebbe piazzata l'acqua
        BlockPos pos = event.getHitVec().getBlockPos().relative(event.getFace());

        // Controlla se è sopra l'altezza minima
        if (pos.getY() < ConfigManager.minWaterEvaporationHeight) {
            return; // Permetti piazzamento sotto l'altezza minima
        }

        // Evapora l'acqua
        level.levelEvent(2001, pos, Block.getId(Blocks.WATER.defaultBlockState()));
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH,
                SoundSource.BLOCKS, 0.5F, 2.6F + (level.random.nextFloat() - level.random.nextFloat()) * 0.8F);

        player.setItemInHand(event.getHand(), Items.BUCKET.getDefaultInstance());

        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }
}