package com.creatormc.judgementdaymod.events.mixin.client;

import com.creatormc.judgementdaymod.handlers.ClientPlayerEventHandler;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The client got its new player (respawn), where Forge fires its ClientPlayerNetworkEvent.Clone. */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void judgementday$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ClientPlayerEventHandler.onPlayerClone();
    }
}
