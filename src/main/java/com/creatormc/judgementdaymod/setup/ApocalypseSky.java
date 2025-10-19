package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class ApocalypseSky extends DimensionSpecialEffects {

    public ApocalypseSky() {
        // todo con skytype none si ottiene un cielo completamente rosso ma sembra un
        // effetto un po' artificiale, valutare, si perde luna
        super(128.0F, true, DimensionSpecialEffects.SkyType.NORMAL, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        double percent = Phase.toPercent(ConfigManager.apocalypseStage, ConfigManager.apocalypseMaxDays);

        // Sotto il 20% mantieni il comportamento vanilla
        if (percent < 20.0) {
            return color.multiply(
                    brightness * 0.94 + 0.06,
                    brightness * 0.94 + 0.06,
                    brightness * 0.91 + 0.09);
        }

        // Normalizza factor tra 20% e 100% -> 0.0 a 1.0
        double factor = (percent - 20.0) / 80.0;

        // Colori apocalittici
        double apocalypseRed = 1.0;
        double apocalypseGreen = 0.2;
        double apocalypseBlue = 0.3;

        // Interpolazione tra vanilla e apocalisse
        double vanillaMultiplierRG = brightness * 0.94 + 0.06;
        double vanillaMultiplierB = brightness * 0.91 + 0.09;

        double red = color.x * (vanillaMultiplierRG * (1 - factor) + apocalypseRed * factor);
        double green = color.y * (vanillaMultiplierRG * (1 - factor) + apocalypseGreen * factor);
        double blue = color.z * (vanillaMultiplierB * (1 - factor) + apocalypseBlue * factor);

        return new Vec3(red, green, blue);
    }

    @Override
    public boolean isFoggyAt(int x, int z) {
        return false;
    }

}