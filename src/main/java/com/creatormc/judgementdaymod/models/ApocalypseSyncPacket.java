package com.creatormc.judgementdaymod.models;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import com.creatormc.judgementdaymod.utilities.ConfigManager;

/**
 * The apocalypse state the client needs to draw it (see ClientApocalypseData).
 */
public class ApocalypseSyncPacket {
    private final int currentDay;
    private final int maxDay;
    private final int endDay;
    private final int damageHeight;
    private final boolean active;

    public ApocalypseSyncPacket(int currentDay, int maxDay, int endDay, int damageHeight, boolean active) {
        this.currentDay = currentDay;
        this.maxDay = maxDay;
        this.endDay = endDay;
        this.damageHeight = damageHeight;
        this.active = active;
    }

    /** The server's current state. */
    public static ApocalypseSyncPacket current() {
        return new ApocalypseSyncPacket(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays,
                ConfigManager.apocalypseEndDay, ConfigManager.minDamageHeight, ConfigManager.apocalypseActive);
    }

    public ApocalypseSyncPacket(FriendlyByteBuf buf) {
        this.currentDay = buf.readInt();
        this.maxDay = buf.readInt();
        this.endDay = buf.readInt();
        this.damageHeight = buf.readInt();
        this.active = buf.readBoolean();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(currentDay);
        buf.writeInt(maxDay);
        buf.writeInt(endDay);
        buf.writeInt(damageHeight);
        buf.writeBoolean(active);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // Client side only: the server's own state is not touched (same classes in single player)
        ctx.get().enqueueWork(() -> ClientApocalypseData.set(currentDay, maxDay, endDay, damageHeight, active));
        ctx.get().setPacketHandled(true);
    }
}
