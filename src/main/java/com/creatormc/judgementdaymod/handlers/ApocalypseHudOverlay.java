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

        // Compute normalized progress (0 = start, 1 = end)
        float progress = (float) currentDay / (float) ConfigManager.apocalypseMaxDays;
        progress = Math.min(1.0F, Math.max(0.0F, progress));

        // Color transitions from dull green → yellow → orange → red
        float r = 0.4F + progress * 0.6F; // starts darker (0.4) and rises to full red
        float g = 0.8F - progress * 0.7F; // starts at 0.8 and fades out
        float b = 0.3F - progress * 0.3F; // start slightly warm, fades to zero

        if (level != null && daysLeft <= 5) {
            float pulse = (float) (Math.sin(level.getGameTime() * 0.3F) * 0.25F + 0.75F);
            r *= pulse;
            g *= pulse;
            b *= pulse;
        }

        int color = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);

        Font font = mc.font;
        String text = "Days left: " + daysLeft;

        // Coordinates (bottom-left corner)
        int x = 10;
        int y = event.getWindow().getGuiScaledHeight() - 20;

        RenderSystem.enableBlend();

        // Scale up the text (e.g. 1.5x)
        float scale = 1.5F;
        var poseStack = event.getGuiGraphics().pose();
        poseStack.pushPose();
        poseStack.scale(scale, scale, 1.0F);

        // Adjust position so it stays in bottom-left corner after scaling
        x = (int) (10 / scale);
        y = (int) ((event.getWindow().getGuiScaledHeight() - 20) / scale);

        // Draw the text
        event.getGuiGraphics().drawString(
                font,
                text,
                x,
                y,
                color,
                true // draw with shadow
        );

        poseStack.popPose();
        RenderSystem.disableBlend();
    }
}
