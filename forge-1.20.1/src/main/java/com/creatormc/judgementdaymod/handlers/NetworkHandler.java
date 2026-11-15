package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.models.ApocalypseSyncPacket;
import com.creatormc.judgementdaymod.models.HeatSyncPacket;
import com.creatormc.judgementdaymod.models.PhaseTitlePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.server.level.ServerPlayer;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "2";
    private static int packetId = 0;
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("judgementday", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    public static void register() {
        INSTANCE.registerMessage(packetId++, HeatSyncPacket.class,
                HeatSyncPacket::toBytes,
                HeatSyncPacket::new,
                HeatSyncPacket::handle);

        INSTANCE.registerMessage(packetId++, ApocalypseSyncPacket.class,
                ApocalypseSyncPacket::toBytes,
                ApocalypseSyncPacket::new,
                ApocalypseSyncPacket::handle);

        INSTANCE.registerMessage(packetId++, PhaseTitlePacket.class,
                PhaseTitlePacket::toBytes,
                PhaseTitlePacket::new,
                PhaseTitlePacket::handle);
    }

    public static void sendToClient(Object msg, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void sendToAll(Object msg) {
        INSTANCE.send(PacketDistributor.ALL.noArg(), msg);
    }
}
