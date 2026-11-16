package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.events.mixin.client.DimensionSpecialEffectsAccessor;
import com.creatormc.judgementdaymod.handlers.ApocalypseHudOverlay;
import com.creatormc.judgementdaymod.handlers.ClientPlayerEventHandler;
import com.creatormc.judgementdaymod.handlers.HeatOverlay;
import com.creatormc.judgementdaymod.handlers.NetworkHandler;
import com.creatormc.judgementdaymod.handlers.PhaseTitleOverlay;
import com.creatormc.judgementdaymod.models.ApocalypseSyncPacket;
import com.creatormc.judgementdaymod.models.HeatSyncPacket;
import com.creatormc.judgementdaymod.models.PhaseTitlePacket;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.resources.ResourceLocation;

public class ClientSetup implements ClientModInitializer {

    // Overlays of the Forge HUD (its vanilla ones): Forge draws the heat bar after each of
    // them (RenderGuiOverlayEvent.Post), so its translucent background and glow add up
    private static final int FORGE_HUD_OVERLAYS = 26;

    @Override
    public void onInitializeClient() {
        // The apocalypse sky replaces the overworld's (Forge's registration event replaces it too)
        DimensionSpecialEffectsAccessor.getEffects().put(new ResourceLocation("minecraft", "overworld"),
                new ApocalypseSky());

        // Messages of the server, handled on the client's main thread (as Forge's handlers did)
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.HEAT, (client, handler, buf, sender) -> {
            final HeatSyncPacket packet = new HeatSyncPacket(buf);
            client.execute(packet::apply);
        });
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.APOCALYPSE, (client, handler, buf, sender) -> {
            final ApocalypseSyncPacket packet = new ApocalypseSyncPacket(buf);
            client.execute(packet::apply);
        });
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.PHASE_TITLE, (client, handler, buf, sender) -> {
            final PhaseTitlePacket packet = new PhaseTitlePacket(buf);
            client.execute(packet::apply);
        });

        // End of the HUD (Forge's RenderGuiEvent.Post, after its overlays)
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            for (int i = 0; i < FORGE_HUD_OVERLAYS; i++)
                HeatOverlay.onRenderOverlay(graphics);
            ApocalypseHudOverlay.onRenderOverlay(graphics);
            PhaseTitleOverlay.onRenderOverlay(graphics);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPlayerEventHandler.onLoggingOut());

        // The client probe of the self test harness, when it is built in (never in a release jar)
        try {
            Class.forName("com.creatormc.judgementdaymod.selftest.ClientProbe").getMethod("init").invoke(null);
        } catch (ClassNotFoundException e) {
            // not a test build
        } catch (ReflectiveOperationException e) {
            JudgementDayMod.LOGGER.error("[JudgementDayMod] Could not start the client probe", e);
        }
    }
}
