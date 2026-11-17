package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.elements.AshBlock;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public class ModBlocks {
        public static final Registered<Block> ASH_BLOCK = new Registered<>(new AshBlock());

        static void register() {
                Registry.register(BuiltInRegistries.BLOCK, new ResourceLocation(JudgementDayMod.MODID, "ash_block"),
                                ASH_BLOCK.get());
        }
}
