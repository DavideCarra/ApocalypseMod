package com.creatormc.judgementdaymod.setup;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
            JudgementDayMod.MODID);

    public static final RegistryObject<Item> MIO_BLOCCO_ITEM = ITEMS.register("ash_block",
            () -> new BlockItem(ModBlocks.ASH_BLOCK.get(),
                    new Item.Properties()));
}