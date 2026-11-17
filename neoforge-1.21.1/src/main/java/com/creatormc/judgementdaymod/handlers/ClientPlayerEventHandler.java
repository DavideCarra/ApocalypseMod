package com.creatormc.judgementdaymod.handlers;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.creatormc.judgementdaymod.models.ClientApocalypseData;
import com.creatormc.judgementdaymod.models.ClientHeatData;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;

@EventBusSubscriber(modid = JudgementDayMod.MODID, value = Dist.CLIENT)
public class ClientPlayerEventHandler {

    @SubscribeEvent
    public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        ClientHeatData.set(0f); // reset heat bar on respawn
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // The next world or server sends its own state
        ClientApocalypseData.reset();
        ClientHeatData.set(0f);
        PhaseTitleOverlay.clear();
    }
}
