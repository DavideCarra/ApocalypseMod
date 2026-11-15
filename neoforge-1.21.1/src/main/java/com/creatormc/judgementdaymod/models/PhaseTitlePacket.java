package com.creatormc.judgementdaymod.models;


import com.creatormc.judgementdaymod.handlers.PhaseTitleOverlay;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Shows the title of a phase (or of the end) on the client.
 */
public class PhaseTitlePacket implements CustomPacketPayload {
    public static final Type<PhaseTitlePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("judgementday", "phase_title"));
    public static final StreamCodec<FriendlyByteBuf, PhaseTitlePacket> CODEC = StreamCodec.of(
            (buf, packet) -> packet.toBytes(buf), PhaseTitlePacket::new);

    @Override
    public Type<PhaseTitlePacket> type() {
        return TYPE;
    }

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

    public void handle(IPayloadContext ctx) {
        // Client bound only, run on the client's main thread (see NetworkHandler)
        PhaseTitleOverlay.displayPhaseTitle(phase.getTitleComponent(), phase.getDescriptionComponent());
    }
}
