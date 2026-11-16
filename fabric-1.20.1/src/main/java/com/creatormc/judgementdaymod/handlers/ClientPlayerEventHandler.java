package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.models.ClientApocalypseData;
import com.creatormc.judgementdaymod.models.ClientHeatData;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;

public class ClientPlayerEventHandler {

    public static void onPlayerClone() {
        ClientHeatData.set(0f); // reset heat bar on respawn
    }

    public static void onLoggingOut() {
        // The next world or server sends its own state
        ClientApocalypseData.reset();
        ClientHeatData.set(0f);
    }
}
