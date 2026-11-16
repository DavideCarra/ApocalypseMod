package com.creatormc.judgementdaymod.events.mixin;

import com.creatormc.judgementdaymod.events.fabric.ModEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player uses a block, where Forge fires its PlayerInteractEvent.RightClickBlock: after
 * the check of the block's feature flags, before the spectator check.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void judgementday$onUseItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        // Vanilla fails the use of a disabled block before Forge's event
        if (!level.getBlockState(hit.getBlockPos()).getBlock().isEnabled(level.enabledFeatures()))
            return;
        final InteractionResult result = ModEvents.rightClickBlock(player, hand, hit);
        if (result != null)
            cir.setReturnValue(result);
    }
}
