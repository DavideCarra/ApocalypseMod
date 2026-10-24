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
    // Before the apocalypse starts counting (startDelayDays)
    private final boolean delayed;
    // HUD the server lets the players show
    private final boolean showDayCounter;
    private final boolean showHeatBar;

    public ApocalypseSyncPacket(int currentDay, int maxDay, int endDay, int damageHeight, boolean active,
            boolean delayed, boolean showDayCounter, boolean showHeatBar) {
        this.currentDay = currentDay;
        this.maxDay = maxDay;
        this.endDay = endDay;
        this.damageHeight = damageHeight;
        this.active = active;
        this.delayed = delayed;
        this.showDayCounter = showDayCounter;
        this.showHeatBar = showHeatBar;
    }

    /** The server's current state. */
    public static ApocalypseSyncPacket current() {
        return new ApocalypseSyncPacket(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays,
                ConfigManager.apocalypseEndDay, ConfigManager.minDamageHeight, ConfigManager.apocalypseActive,
                ConfigManager.startDelayDays > 0, ConfigManager.showDayCounter, ConfigManager.showHeatBar);
    }

    public ApocalypseSyncPacket(FriendlyByteBuf buf) {
        this.currentDay = buf.readInt();
        this.maxDay = buf.readInt();
        this.endDay = buf.readInt();
        this.damageHeight = buf.readInt();
        this.active = buf.readBoolean();
        this.delayed = buf.readBoolean();
        this.showDayCounter = buf.readBoolean();
        this.showHeatBar = buf.readBoolean();
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(currentDay);
        buf.writeInt(maxDay);
        buf.writeInt(endDay);
        buf.writeInt(damageHeight);
        buf.writeBoolean(active);
        buf.writeBoolean(delayed);
        buf.writeBoolean(showDayCounter);
        buf.writeBoolean(showHeatBar);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // Client side only: the server's own state is not touched (same classes in single player)
        ctx.get().enqueueWork(() -> ClientApocalypseData.set(currentDay, maxDay, endDay, damageHeight, active,
                delayed, showDayCounter, showHeatBar));
        ctx.get().setPacketHandled(true);
    }
}
