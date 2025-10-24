package com.creatormc.judgementdaymod.handlers;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.creatormc.judgementdaymod.models.ClientHeatData;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, value = Dist.CLIENT)
public class ClientPlayerEventHandler {

    @SubscribeEvent
    public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        ClientHeatData.set(0f); // reset heat bar on respawn
    }
}
