package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.handlers.ApocalypseDamageHandler;
import com.creatormc.judgementdaymod.handlers.DayTracker;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player joined (end of placeNewPlayer) or respawned (end of respawn), where Forge fires
 * its PlayerLoggedInEvent and PlayerRespawnEvent.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void judgementday$onPlaceNewPlayer(Connection connection, ServerPlayer player, CallbackInfo ci) {
        DayTracker.onPlayerLoggedIn(player);
    }

    @Inject(method = "respawn", at = @At("RETURN"))
    private void judgementday$onRespawn(ServerPlayer old, boolean keepEverything,
            CallbackInfoReturnable<ServerPlayer> cir) {
        ApocalypseDamageHandler.onPlayerRespawn(cir.getReturnValue());
    }
}
