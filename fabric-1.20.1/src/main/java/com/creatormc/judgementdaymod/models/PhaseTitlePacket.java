package com.creatormc.judgementdaymod.models;

import com.creatormc.judgementdaymod.handlers.NetworkHandler;
import net.minecraft.resources.ResourceLocation;


import com.creatormc.judgementdaymod.handlers.PhaseTitleOverlay;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Shows the title of a phase (or of the end) on the client.
 */
public class PhaseTitlePacket implements NetworkHandler.Message {

    @Override
    public ResourceLocation id() {
        return NetworkHandler.PHASE_TITLE;
    }

    private final Phase phase;

    public PhaseTitlePacket(Phase phase) {
        this.phase = phase;
    }

    public PhaseTitlePacket(FriendlyByteBuf buf) {
        this.phase = buf.readEnum(Phase.class);
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeEnum(phase);
    }

    /** On the client's main thread. */
    public void apply() {
        PhaseTitleOverlay.displayPhaseTitle(phase.getTitleComponent(), phase.getDescriptionComponent());
    }
}
