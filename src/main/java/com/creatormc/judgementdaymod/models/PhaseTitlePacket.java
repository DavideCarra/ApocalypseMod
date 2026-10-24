package com.creatormc.judgementdaymod.models;

import java.util.function.Supplier;

import com.creatormc.judgementdaymod.handlers.PhaseTitleOverlay;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Shows the title of a phase (or of the end) on the client.
 */
public class PhaseTitlePacket {
    private final Phase phase;

    public PhaseTitlePacket(Phase phase) {
        this.phase = phase;
    }

    public PhaseTitlePacket(FriendlyByteBuf buf) {
        this.phase = buf.readEnum(Phase.class);
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeEnum(phase);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PhaseTitleOverlay.displayPhaseTitle(phase.getTitleComponent(),
                        phase.getDescriptionComponent())));
        ctx.get().setPacketHandled(true);
    }
}
