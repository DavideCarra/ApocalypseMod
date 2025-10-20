package com.creatormc.judgementdaymod.elements;

import com.creatormc.judgementdaymod.utilities.Analyzer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
    public int getDustColor(BlockState state, BlockGetter reader, BlockPos pos) {
        return 0x6B6B6B; // Dark gray for ash
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SPREADS_LEFT);
    }

    // Optional: makes ash fall more slowly
    @Override
    protected void falling(FallingBlockEntity fallingEntity) {
        fallingEntity.setDeltaMovement(
                fallingEntity.getDeltaMovement().multiply(1.0, 0.8, 1.0));
    }

}
