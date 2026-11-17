package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.handlers.NetworkHandler;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(JudgementDayMod.MODID)
public class JudgementDayMod {
    public static final String MODID = "judgementday";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public JudgementDayMod(FMLJavaModLoadingContext context) {
    IEventBus modEventBus = context.getModEventBus();

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        CHUNK_GENERATORS.register(modEventBus);

        modEventBus.addListener(JudgementDayMod::commonSetup);
    }

    private static void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::register);
    }

    public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS = DeferredRegister
            .create(Registries.CHUNK_GENERATOR, MODID);

    public static final RegistryObject<Codec<ApocalypseChunkGenerator>> APOCALYPSE_WORLD = CHUNK_GENERATORS
            .register("apocalypse", () -> ApocalypseChunkGenerator.CODEC);
}
