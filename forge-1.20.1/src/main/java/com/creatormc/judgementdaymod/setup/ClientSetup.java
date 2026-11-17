package com.creatormc.judgementdaymod.setup;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public class ClientEvents {
        @SubscribeEvent
        public static void onRegisterDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
            event.register(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), new ApocalypseSky());

        }
    }
}
