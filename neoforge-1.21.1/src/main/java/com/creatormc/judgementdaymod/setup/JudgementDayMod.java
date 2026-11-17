package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.handlers.NetworkHandler;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(JudgementDayMod.MODID)
public class JudgementDayMod {
    public static final String MODID = "judgementday";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public JudgementDayMod(IEventBus modEventBus) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        CHUNK_GENERATORS.register(modEventBus);

        modEventBus.addListener(NetworkHandler::register);
    }

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS = DeferredRegister
            .create(Registries.CHUNK_GENERATOR, MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<ApocalypseChunkGenerator>> APOCALYPSE_WORLD = CHUNK_GENERATORS
            .register("apocalypse", () -> ApocalypseChunkGenerator.CODEC);
}
