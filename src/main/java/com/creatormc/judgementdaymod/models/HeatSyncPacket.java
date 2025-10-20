package com.creatormc.judgementdaymod.models;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class HeatSyncPacket {
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

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> ClientHeatData.set(heat));
        context.get().setPacketHandled(true);
    }
}
