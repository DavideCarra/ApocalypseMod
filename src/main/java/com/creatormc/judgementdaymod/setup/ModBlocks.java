package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.elements.AshBlock;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
        public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS,
                        JudgementDayMod.MODID);

        public static final RegistryObject<Block> ASH_BLOCK = BLOCKS.register("ash_block", AshBlock::new);
}