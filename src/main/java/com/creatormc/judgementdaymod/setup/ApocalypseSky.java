package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.phys.Vec3;

public class ApocalypseSky extends DimensionSpecialEffects {

    public ApocalypseSky() {
        // Note: using SkyType.NONE results in a completely red sky but removes the moon
        super(128.0F, true, DimensionSpecialEffects.SkyType.NORMAL, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        double percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

        // Below 20%, keep vanilla behavior
        if (percent < 20.0) {
            return color.multiply(
                    brightness * 0.94 + 0.06,
                    brightness * 0.94 + 0.06,
                    brightness * 0.91 + 0.09);
        }

        // Normalize factor between 20% and 100% -> 0.0 to 1.0
        double factor = (percent - 20.0) / 80.0;

        // Apocalypse tint colors
        double apocalypseRed = 1.0;
        double apocalypseGreen = 0.2;
        double apocalypseBlue = 0.3;

        // Interpolation between vanilla and apocalypse color
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
