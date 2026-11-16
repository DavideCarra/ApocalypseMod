package com.creatormc.judgementdaymod.elements;

import javax.annotation.Nonnull;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public class AshBlock extends FallingBlock {

    public static final IntegerProperty SPREADS_LEFT = IntegerProperty.create("spreads_left", 0, 1);

    public AshBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(0.05f, 0.05f) // Extremely fragile
                .sound(SoundType.SAND)
                .lightLevel((state) -> 0) // Does not emit light
                .speedFactor(0.7f) // Slows movement more
                .jumpFactor(0.9f) // Makes jumping harder
                .friction(0.9f));
        this.registerDefaultState(this.stateDefinition.any().setValue(SPREADS_LEFT, 1));
    }

    @Override
    public int getDustColor(@Nonnull BlockState state, @Nonnull BlockGetter reader, @Nonnull BlockPos pos) {
        return 0x6B6B6B; // Dark gray for ash
    }

    @Override
    protected void createBlockStateDefinition(@Nonnull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SPREADS_LEFT);
    }

    // Optional: makes ash fall more slowly
    @Override
    protected void falling(@Nonnull FallingBlockEntity fallingEntity) {
        fallingEntity.setDeltaMovement(
                fallingEntity.getDeltaMovement().multiply(1.0, 0.8, 1.0));
    }

}
