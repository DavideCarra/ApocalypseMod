package com.creatormc.judgementdaymod.setup;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
        public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
                        JudgementDayMod.MODID);

        public static final RegistryObject<Item> ASH_BLOCK = ITEMS.register("ash_block",
                        () -> new BlockItem(ModBlocks.ASH_BLOCK.get(),
                                        new Item.Properties()));

        public static final RegistryObject<Item> HEART_OF_OBLIVION = ITEMS.register(
                        "heart_of_oblivion",
                        () -> new Item(new Item.Properties()
                                        .stacksTo(1)
                                        .rarity(Rarity.RARE) 
                                        .fireResistant() 
                        ));
}