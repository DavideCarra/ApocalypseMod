package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.blaze3d.systems.RenderSystem;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, value = Dist.CLIENT)
public class ApocalypseHudOverlay {

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        // Only render in-game (not in menu)
        if (mc.player == null || level == null)
            return;

        int maxDay = ConfigManager.apocalypseMaxDays;
        int currentDay = ConfigManager.apocalypseCurrentDay;

        // daysLeft is the absolute difference between current day and the end day
        int daysLeft = Math.abs(maxDay - currentDay);

        // Skip rendering if apocalypse is inactive or finished
        if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseMaxDays) {
            return;
        }

        // Get screen dimensions before scaling
        int screenWidth = event.getWindow().getGuiScaledWidth();
        int screenHeight = event.getWindow().getGuiScaledHeight();

        // Compute normalized progress (0 = start, 1 = end)
        float progress = (float) currentDay / (float) maxDay;
        progress = Math.min(1.0F, Math.max(0.0F, progress));

        // Color transitions from dull green → yellow → orange → red
        float r = 0.4F + progress * 0.6F;
        float g = 0.8F - progress * 0.7F;
        float b = 0.3F - progress * 0.3F;

        // Pulse effect when less than 5 days left
        if (daysLeft <= 5) {
            float pulse = (float) (Math.sin(level.getGameTime() * 0.3F) * 0.25F + 0.75F);
            r *= pulse;
            g *= pulse;
            b *= pulse;
        }

        int color = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);

        Font font = mc.font;
        String text = "Days left: " + daysLeft;

        // Calculate adaptive scale based on screen width
        // Smaller screens get smaller text, larger screens get larger text
        float scale = Math.min(1.5F, screenWidth / 800.0F);
        
        // Ensure minimum readable size
        scale = Math.max(0.8F, scale);

        var poseStack = event.getGuiGraphics().pose();
        poseStack.pushPose();
        poseStack.scale(scale, scale, 1.0F);

        // Calculate text width to prevent overflow
        int textWidth = (int) (font.width(text) * scale);
        
        // Position text in bottom-left, accounting for scale
        int x = (int) (10 / scale);
        int y = (int) ((screenHeight - 20) / scale);

        // Ensure text doesn't go off-screen
        if (textWidth + 10 > screenWidth) {
            scale *= (screenWidth - 20) / (float) textWidth;
            poseStack.scale(scale / 1.5F, scale / 1.5F, 1.0F);
            x = (int) (10 / scale);
            y = (int) ((screenHeight - 20) / scale);
        }

        RenderSystem.enableBlend();

        // Draw the text with shadow for better readability
        event.getGuiGraphics().drawString(
                font,
                text,
                x,
                y,
                color,
                true
        );

        poseStack.popPose();
        RenderSystem.disableBlend();
    }
}