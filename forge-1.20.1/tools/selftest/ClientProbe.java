package com.creatormc.judgementdaymod.selftest;

import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Client side probe of the dev client (not part of the mod): while run/probe.flag
 * exists, every 5 seconds it logs how many columns around the player have less than
 * full sky light right above their top block, as the client sees them.
 */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, value = Dist.CLIENT)
public class ClientProbe {

    private static int ticks;

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ++ticks % 100 != 0)
            return;
        if (!Files.exists(FMLPaths.GAMEDIR.get().resolve("probe.flag")))
            return;
        ClientLevel level = mc.level;
        BlockPos c = mc.player.blockPosition();
        int radius = 40, columns = 0, dark = 0;
        Map<String, Integer> perChunk = new TreeMap<>();
        StringBuilder samples = new StringBuilder();
        for (int x = c.getX() - radius; x <= c.getX() + radius; x++) {
            for (int z = c.getZ() - radius; z <= c.getZ() + radius; z++) {
                if (!level.hasChunk(x >> 4, z >> 4))
                    continue;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos p = new BlockPos(x, top, z);
                columns++;
                int sky = level.getBrightness(LightLayer.SKY, p);
                if (sky < 15) {
                    dark++;
                    perChunk.merge((x >> 4) + ":" + (z >> 4), 1, Integer::sum);
                    if (dark <= 4) {
                        samples.append(" [").append(x).append(' ').append(top).append(' ').append(z)
                                .append(" sky=").append(sky).append(" lowestSource=")
                                .append(level.getChunk(x >> 4, z >> 4).getSkyLightSources().getLowestSourceY(x & 15, z & 15))
                                .append(" below=").append(level.getBlockState(p.below()).getBlock()).append(']');
                    }
                }
            }
        }
        JudgementDayMod.LOGGER.info("[PROBE] at " + c.toShortString() + " columns=" + columns + " dark=" + dark
                + " chunks=" + perChunk + samples);
    }
}
