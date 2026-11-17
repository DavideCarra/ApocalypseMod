package com.creatormc.judgementdaymod.setup;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
        public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(JudgementDayMod.MODID);

        public static final DeferredItem<Item> ASH_BLOCK = ITEMS.register("ash_block",
                        () -> new BlockItem(ModBlocks.ASH_BLOCK.get(),
                                        new Item.Properties()));

        public static final DeferredItem<Item> HEART_OF_OBLIVION = ITEMS.register(
                        "heart_of_oblivion",
                        () -> new Item(new Item.Properties()
                                        .stacksTo(1)
                                        .rarity(Rarity.RARE) 
                                        .fireResistant() 
                        ));
}
