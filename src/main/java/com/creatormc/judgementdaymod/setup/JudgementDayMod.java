package com.creatormc.judgementdaymod.setup;

import com.mojang.serialization.Codec;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

@Mod(JudgementDayMod.MODID)
public class JudgementDayMod {
    public static final String MODID = "judgementday";

    public JudgementDayMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        CHUNK_GENERATORS.register(modEventBus);
    }

    // 1. DeferredRegister legato al registry dei ChunkGenerator
    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS = DeferredRegister
            .create(Registries.CHUNK_GENERATOR, MODID);

    // 2. Registrazione del tuo generator col nome "Apocalypse"
    public static final RegistryObject<Codec<ApocalypseChunkGenerator>> APOCALYPSE_WORLD = CHUNK_GENERATORS
            .register("apocalypse", () -> ApocalypseChunkGenerator.CODEC);
}
