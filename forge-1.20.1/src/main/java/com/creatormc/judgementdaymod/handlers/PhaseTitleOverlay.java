package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, value = Dist.CLIENT)
public class PhaseTitleOverlay {

    private static Component currentTitle = null;
    private static Component currentSubtitle = null;
    private static long displayStartTime = 0;
    private static final long DISPLAY_DURATION = 100; // 5 seconds (100 ticks)
    private static final long FADE_IN_DURATION = 10;
    private static final long FADE_OUT_DURATION = 20;

    public static void displayPhaseTitle(Component title, Component subtitle) {
        currentTitle = title;
        currentSubtitle = subtitle;
        displayStartTime = System.currentTimeMillis() / 50; // Convert to ticks
    }

    public static void clear() {
        currentTitle = null;
        currentSubtitle = null;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiEvent.Post event) {
        if (currentTitle == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        long currentTime = System.currentTimeMillis() / 50;
        long elapsedTicks = currentTime - displayStartTime;

        // Clear after duration
        if (elapsedTicks > DISPLAY_DURATION) {
            clear();
            return;
        }

        // Calculate alpha for fade in/out
        float alpha = 1.0F;
        if (elapsedTicks < FADE_IN_DURATION) {
            alpha = (float) elapsedTicks / FADE_IN_DURATION;
        } else if (elapsedTicks > DISPLAY_DURATION - FADE_OUT_DURATION) {
            alpha = (float) (DISPLAY_DURATION - elapsedTicks) / FADE_OUT_DURATION;
        }

        int screenWidth = event.getWindow().getGuiScaledWidth();
        int screenHeight = event.getWindow().getGuiScaledHeight();
        Font font = mc.font;

        var poseStack = event.getGuiGraphics().pose();
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Calculate title scale (adaptive)
        String titleText = currentTitle.getString();
        float maxTitleScale = Math.min(3.0F, screenWidth / 300.0F);
        float titleScale = maxTitleScale;
        
        int titleWidth = (int) (font.width(titleText) * titleScale);
        if (titleWidth > screenWidth - 40) {
            titleScale = (screenWidth - 40) / (float) font.width(titleText);
        }
        titleScale = Math.max(1.0F, titleScale); // Minimum scale

        // Render TITLE
        poseStack.pushPose();
        poseStack.scale(titleScale, titleScale, 1.0F);
        
        int titleX = (int) ((screenWidth / 2.0F - font.width(titleText) * titleScale / 2.0F) / titleScale);
        int titleY = (int) ((screenHeight / 2.0F - 40) / titleScale);
        
        int titleColor = 0xFFFFFF | ((int) (alpha * 255) << 24);
        
        event.getGuiGraphics().drawString(
            font,
            currentTitle,
            titleX,
            titleY,
            titleColor,
            true
        );
        poseStack.popPose();

        // Render SUBTITLE if present
        if (currentSubtitle != null) {
            String subtitleText = currentSubtitle.getString();
            float maxSubtitleScale = Math.min(1.5F, screenWidth / 500.0F);
            float subtitleScale = maxSubtitleScale;
            
            int subtitleWidth = (int) (font.width(subtitleText) * subtitleScale);
            if (subtitleWidth > screenWidth - 40) {
                subtitleScale = (screenWidth - 40) / (float) font.width(subtitleText);
            }
            subtitleScale = Math.max(0.8F, subtitleScale);

            poseStack.pushPose();
            poseStack.scale(subtitleScale, subtitleScale, 1.0F);
            
            int subtitleX = (int) ((screenWidth / 2.0F - font.width(subtitleText) * subtitleScale / 2.0F) / subtitleScale);
            int subtitleY = (int) ((screenHeight / 2.0F + 10) / subtitleScale);
            
            int subtitleColor = 0xFFFFFF | ((int) (alpha * 255) << 24);
            
            event.getGuiGraphics().drawString(
                font,
                currentSubtitle,
                subtitleX,
                subtitleY,
                subtitleColor,
                true
            );
            poseStack.popPose();
        }

        RenderSystem.disableBlend();
    }
}