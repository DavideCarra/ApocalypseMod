package com.creatormc.judgementdaymod.eventHandlers;

import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.ContainerScreenEvent.Render;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, value = Dist.CLIENT)
public class BigSunRenderer {

    // uses a custom texture for the sun identical to the original but without
    // the black background
    private static final ResourceLocation SUN_TEXTURE = new ResourceLocation(JudgementDayMod.MODID,
            "textures/environment/sun.png");

    @SubscribeEvent
    public static void onRenderSky(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY)
            return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || !level.dimensionType().hasSkyLight() || player == null)
            return;

        boolean isAboveGround;
        int yThreshold = 0; // reference height

        if (player.getY() >= yThreshold) {
            // Above height: filter always active
            isAboveGround = true;
        } else {
            // Below height: filter only if the sky is visible
            isAboveGround = level.canSeeSky(player.blockPosition());
        }
        // If apocalypse is over, do not change sun rendering
        if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseEndDay) {
            return;
        }

        if (!isAboveGround) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F); // reset
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        // === Correct vanilla rotation ===
        float sunAngle = level.getTimeOfDay(event.getPartialTick()) * 360.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(sunAngle));

        // === Dynamic parameters based on phase ===
        float stage = (float) Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);
        float factor = stage / 100.0F;
        long time = level.getGameTime();
        float pulse = 1.0F; // base value
        // When factor = 0, vanilla behavior
        if (factor < 0.2F) {
            poseStack.popPose();
            return; // Do not modify the vanilla sun
        }
        if (factor > 0.8F) { // active only in the last phases
            float intensity = (factor - 0.8F) * 5.0F; // 0 → 1 between 80% and 100%
            pulse = 1.0F + 0.05F * intensity * (float) Math.sin(time * 0.5F);
        }
        float scale = (factor) * pulse; // dynamic size
        float distance = 100.0F - factor; // the sun gets closer
        float size = 30.0F * scale; // vanilla base 30

        // Gradually redder color
        float r = 1.0F;
        float g = 1.0F - factor; // less green
        float b = 0.5F - 0.4F * factor; // warmer tone

        // === Rendering state ===
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        RenderSystem.setShaderColor(r, g, b, 1.0F); // apply color tint

        Matrix4f mat = poseStack.last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        // === Sun ===
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(mat, -size, distance, -size).uv(0.0F, 0.0F).endVertex();
        buf.vertex(mat, size, distance, -size).uv(1.0F, 0.0F).endVertex();
        buf.vertex(mat, size, distance, size).uv(1.0F, 1.0F).endVertex();
        buf.vertex(mat, -size, distance, size).uv(0.0F, 1.0F).endVertex();
        tess.end();

        // === Restore state ===
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

}
