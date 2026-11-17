package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.models.ApocalypseSyncPacket;
import com.creatormc.judgementdaymod.models.HeatSyncPacket;
import com.creatormc.judgementdaymod.models.PhaseTitlePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "2";

    // Handled on the client's main thread (as the work Forge's handlers enqueued)
    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(HeatSyncPacket.TYPE, HeatSyncPacket.CODEC, HeatSyncPacket::handle);
        registrar.playToClient(ApocalypseSyncPacket.TYPE, ApocalypseSyncPacket.CODEC, ApocalypseSyncPacket::handle);
        registrar.playToClient(PhaseTitlePacket.TYPE, PhaseTitlePacket.CODEC, PhaseTitlePacket::handle);
    }

    public static void sendToClient(CustomPacketPayload msg, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, msg);
    }

    public static void sendToAll(CustomPacketPayload msg) {
        PacketDistributor.sendToAllPlayers(msg);
    }
}
