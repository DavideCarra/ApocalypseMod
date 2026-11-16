package com.creatormc.judgementdaymod.events.mixin.client;

import com.creatormc.judgementdaymod.handlers.BigSunRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right after the sky is drawn, where Forge's AFTER_SKY render stage is, with the same pose. */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V"))
    private void judgementday$afterSky(PoseStack poseStack, float partialTick, long finishNanoTime,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projection, CallbackInfo ci) {
        BigSunRenderer.onRenderSky(poseStack, partialTick);
    }
}
