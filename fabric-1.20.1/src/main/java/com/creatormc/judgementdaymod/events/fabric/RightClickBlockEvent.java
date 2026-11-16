package com.creatormc.judgementdaymod.events.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A player uses a block, where Forge fires its PlayerInteractEvent.RightClickBlock (on the
 * server, before the spectator check). A cancelled event ends the use with its result.
 */
public final class RightClickBlockEvent {
    private final Player player;
    private final InteractionHand hand;
    private final BlockHitResult hit;
    private boolean canceled;
    private InteractionResult result = InteractionResult.PASS;

    public RightClickBlockEvent(Player player, InteractionHand hand, BlockHitResult hit) {
        this.player = player;
        this.hand = hand;
        this.hit = hit;
    }

    public Player getEntity() {
        return player;
    }

    public Level getLevel() {
        return player.level();
    }

    public InteractionHand getHand() {
        return hand;
    }

    public ItemStack getItemStack() {
        return player.getItemInHand(hand);
    }

    public BlockPos getPos() {
        return hit.getBlockPos();
    }

    public Direction getFace() {
        return hit.getDirection();
    }

    public BlockHitResult getHitVec() {
        return hit;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    public InteractionResult getCancellationResult() {
        return result;
    }

    public void setCancellationResult(InteractionResult result) {
        this.result = result;
    }
}
