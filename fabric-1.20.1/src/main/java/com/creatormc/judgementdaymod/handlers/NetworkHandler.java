package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class NetworkHandler {
    public static final ResourceLocation HEAT = new ResourceLocation(JudgementDayMod.MODID, "heat");
    public static final ResourceLocation APOCALYPSE = new ResourceLocation(JudgementDayMod.MODID, "apocalypse");
    public static final ResourceLocation PHASE_TITLE = new ResourceLocation(JudgementDayMod.MODID, "phase_title");

    /** A message of the mod to the clients. */
    public interface Message {
        ResourceLocation id();

        void toBytes(FriendlyByteBuf buf);
    }

    private static Packet<?> packet(Message msg) {
        final FriendlyByteBuf buf = PacketByteBufs.create();
        msg.toBytes(buf);
        return ServerPlayNetworking.createS2CPacket(msg.id(), buf);
    }

    public static void sendToClient(Message msg, ServerPlayer player) {
        player.connection.send(packet(msg));
    }

    public static void sendToAll(Message msg) {
        final MinecraftServer server = JudgementDayMod.server();
        if (server != null)
            server.getPlayerList().broadcastAll(packet(msg));
    }

}
