package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.elements.AshBlock;

import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
        public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(JudgementDayMod.MODID);

        public static final DeferredBlock<Block> ASH_BLOCK = BLOCKS.register("ash_block", AshBlock::new);
}
