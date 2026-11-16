package com.creatormc.judgementdaymod.setup;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public class ModItems {
        public static final Registered<Item> ASH_BLOCK = new Registered<>(
                        new BlockItem(ModBlocks.ASH_BLOCK.get(), new Item.Properties()));

        public static final Registered<Item> HEART_OF_OBLIVION = new Registered<>(
                        new Item(new Item.Properties()
                                        .stacksTo(1)
                                        .rarity(Rarity.RARE)
                                        .fireResistant()));

        static void register() {
                Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(JudgementDayMod.MODID, "ash_block"),
                                ASH_BLOCK.get());
                Registry.register(BuiltInRegistries.ITEM,
                                new ResourceLocation(JudgementDayMod.MODID, "heart_of_oblivion"),
                                HEART_OF_OBLIVION.get());
        }
}
