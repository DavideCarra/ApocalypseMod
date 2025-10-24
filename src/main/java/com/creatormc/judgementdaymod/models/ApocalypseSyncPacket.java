package com.creatormc.judgementdaymod.models;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import com.creatormc.judgementdaymod.utilities.ConfigManager;

public class ApocalypseSyncPacket {
    private final int currentDay;
    private final int maxDay;
    private final boolean active;

    public ApocalypseSyncPacket(int currentDay, int maxDay, boolean active) {
        this.currentDay = currentDay;
        this.maxDay = maxDay;
        this.active = active;
    }

    public ApocalypseSyncPacket(FriendlyByteBuf buf) {
        this.currentDay = buf.readInt();
        this.maxDay = buf.readInt();
        this.active = buf.readBoolean();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(currentDay);
        buf.writeInt(maxDay);
        buf.writeBoolean(active);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Update client-side config mirror
            ConfigManager.apocalypseCurrentDay = currentDay;
            ConfigManager.apocalypseMaxDays = maxDay;
            ConfigManager.apocalypseActive = active;
        });
        ctx.get().setPacketHandled(true);
    }
}
