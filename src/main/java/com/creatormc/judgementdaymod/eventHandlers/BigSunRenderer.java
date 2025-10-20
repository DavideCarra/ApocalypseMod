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

    // uso una texture personalizzata per il sole uguale a quella originale ma senza
    // il nero sotto
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
        int yThreshold = 0; // altezza di riferimento

        if (player.getY() >= yThreshold) {
            // Sopra altezza: filtro sempre attivo
            isAboveGround = true;
        } else {
            // Sotto altezza: filtro solo se vedi il cielo
            isAboveGround = level.canSeeSky(player.blockPosition());
        }

        if (!isAboveGround) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F); // reset
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        // === Rotazione vanilla corretta ===
        float sunAngle = level.getTimeOfDay(event.getPartialTick()) * 360.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(sunAngle));

        // === Parametri dinamici in base allo stage ===
        float stage = (float) Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);
        float factor = stage / 100.0F;
        long time = level.getGameTime();
        float pulse = 1.0F; // valore base
        // Quando factor = 0, comportamento vanilla
        if (factor < 0.2F) {
            poseStack.popPose();
            return; // Non modificare il sole vanilla
        }
        if (factor > 0.8F) { // attivo solo nelle ultime fasi
            float intensity = (factor - 0.8F) * 5.0F; // 0 → 1 tra 80% e 100%
            pulse = 1.0F + 0.05F * intensity * (float) Math.sin(time * 0.5F);
        }
        float scale = (factor) * pulse; // grandezza dinamica
        float distance = 100.0F - factor; // il sole si avvicina
        float size = 30.0F * scale; // base vanilla 30

        // Colore gradualmente più rosso
        float r = 1.0F;
        float g = 1.0F - factor; // meno verde
        float b = 0.5F - 0.4F * factor; // più caldo

        // === Stato di rendering ===
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, SUN_TEXTURE);
        RenderSystem.setShaderColor(r, g, b, 1.0F); // applica tinta colore

        Matrix4f mat = poseStack.last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        // === Sole ===
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(mat, -size, distance, -size).uv(0.0F, 0.0F).endVertex();
        buf.vertex(mat, size, distance, -size).uv(1.0F, 0.0F).endVertex();
        buf.vertex(mat, size, distance, size).uv(1.0F, 1.0F).endVertex();
        buf.vertex(mat, -size, distance, size).uv(0.0F, 1.0F).endVertex();
        tess.end();

        // === Ripristino stato ===
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

}
