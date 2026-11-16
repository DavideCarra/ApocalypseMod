package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.events.fabric.ChunkUnloadEvent;
import com.creatormc.judgementdaymod.handlers.ApocalypseDamageHandler;
import com.creatormc.judgementdaymod.handlers.DayTracker;
import com.creatormc.judgementdaymod.handlers.UnattendedChunkHandler;
import com.creatormc.judgementdaymod.utilities.OreRetrofit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JudgementDayMod implements ModInitializer {
    public static final String MODID = "judgementday";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    // The running server, from its start to its stop (as Forge's ServerLifecycleHooks)
    private static volatile MinecraftServer server;

    public static MinecraftServer server() {
        return server;
    }

    @Override
    public void onInitialize() {
        ModBlocks.register();
        ModItems.register();
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, new ResourceLocation(MODID, "apocalypse"),
                ApocalypseChunkGenerator.CODEC);

        // The events Fabric API has where Forge fires its own (the others: ModEvents)
        ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STARTED.register(DayTracker::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(UnattendedChunkHandler::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            DayTracker.onServerStopped(s);
            server = null;
        });
        ServerTickEvents.START_SERVER_TICK.register(DayTracker::onServerTickStart);
        ServerTickEvents.END_SERVER_TICK.register(DayTracker::onServerTick);
        ServerTickEvents.END_WORLD_TICK.register(ApocalypseDamageHandler::onLevelTick);
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            final ChunkUnloadEvent event = new ChunkUnloadEvent(level, chunk);
            OreRetrofit.onChunkUnload(event);
            UnattendedChunkHandler.onChunkUnload(event);
        });

        // The self test harness, when it is built in (never in a release jar)
        try {
            Class.forName("com.creatormc.judgementdaymod.selftest.SelfTestCommon").getMethod("init").invoke(null);
        } catch (ClassNotFoundException e) {
            // not a test build
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[JudgementDayMod] Could not start the self test", e);
        }
    }
}
