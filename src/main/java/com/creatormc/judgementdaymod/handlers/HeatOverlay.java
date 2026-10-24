package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.models.ClientApocalypseData;
import com.creatormc.judgementdaymod.models.ClientConfig;
import com.creatormc.judgementdaymod.models.ClientHeatData;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, value = Dist.CLIENT)
public class HeatOverlay {

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // If this player and the server want it
        if (player == null || !ClientConfig.heatBarShown())
            return;

        float heat = ClientHeatData.get();
        // Only while the player is hot: the server heats survival players in the overworld
        // from day 0 to the end day, anywhere else the last heat sent would stay frozen
        if (heat <= 0f || player.isCreative() || player.isSpectator()
                || !player.level().dimension().equals(Level.OVERWORLD)
                || ClientApocalypseData.currentDay < 0
                || ClientApocalypseData.currentDay >= ClientApocalypseData.endDay)
            return;

        float maxHeat = 10f;
        float ratio = heat / maxHeat;

        int width = 100;
        int height = 10;
        int x = (event.getWindow().getGuiScaledWidth() / 2) - (width / 2);
        int y = event.getWindow().getGuiScaledHeight() - 70; // 🔼 higher position

        GuiGraphics g = event.getGuiGraphics();

        // Semi-transparent background with rounded corners
        fillRounded(g, x - 2, y - 2, width + 4, height + 4, 4, 0x66000000);

        // Dynamic color based on heat value
        int red = (int) (255 * ratio);
        int green = (int) (128 * (1.0 - ratio));
        int color = (0xFF << 24) | (red << 16) | (green << 8);

        // Draw inner bar only if > 0
        int barWidth = (int) (width * ratio);
        if (barWidth > 0)
            fillRounded(g, x, y, barWidth, height, 3, color);

        // Glowing border
        if (ratio > 0.7f) {
            int glowColor = 0x55FF3300;
            fillRounded(g, x - 3, y - 3, width + 6, height + 6, 5, glowColor);
        }
    }

    private static void fillRounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + w, y + h - r, color);
        g.fill(x + r, y + r, x + w - r, y + h - r, color);
    }
}
