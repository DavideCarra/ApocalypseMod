package com.creatormc.judgementdaymod.models;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;


public class HeatSyncPacket implements CustomPacketPayload {
    public static final Type<HeatSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("judgementday", "heat"));
    public static final StreamCodec<FriendlyByteBuf, HeatSyncPacket> CODEC = StreamCodec.of(
            (buf, packet) -> packet.toBytes(buf), HeatSyncPacket::new);

    @Override
    public Type<HeatSyncPacket> type() {
        return TYPE;
    }

    private final float heat;

    public HeatSyncPacket(float heat) {
        this.heat = heat;
    }

    public HeatSyncPacket(FriendlyByteBuf buf) {
        this.heat = buf.readFloat();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeFloat(heat);
    }

    public void handle(IPayloadContext context) {
        // Run on the client's main thread (see NetworkHandler)
        ClientHeatData.set(heat);
    }
}
