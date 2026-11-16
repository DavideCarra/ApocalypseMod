package com.creatormc.judgementdaymod.models;

import com.creatormc.judgementdaymod.handlers.NetworkHandler;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.network.FriendlyByteBuf;


public class HeatSyncPacket implements NetworkHandler.Message {

    @Override
    public ResourceLocation id() {
        return NetworkHandler.HEAT;
    }

    private final float heat;

    public HeatSyncPacket(float heat) {
        this.heat = heat;
    }

    public HeatSyncPacket(FriendlyByteBuf buf) {
        this.heat = buf.readFloat();
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeFloat(heat);
    }

    /** On the client's main thread. */
    public void apply() {
        ClientHeatData.set(heat);
    }
}
