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
                .strength(0.05f, 0.05f) // Estremamente fragile
                .sound(SoundType.SAND)
                .lightLevel((state) -> 0) // Non emette luce
                .speedFactor(0.7f) // Rallenta di più il movimento
                .jumpFactor(0.9f) // Rende più difficile saltare
                .friction(0.9f));
        this.registerDefaultState(this.stateDefinition.any().setValue(SPREADS_LEFT, 1));
    }

    @Override
    public int getDustColor(BlockState state, BlockGetter reader, BlockPos pos) {
        return 0x6B6B6B; // Grigio scuro per la cenere
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SPREADS_LEFT);
    }

    // Opzionale: fa sì che la cenere cada più lentamente
    @Override
    protected void falling(FallingBlockEntity fallingEntity) {
        fallingEntity.setDeltaMovement(
                fallingEntity.getDeltaMovement().multiply(1.0, 0.8, 1.0));
    }

 


    /*public void trasformOrBurn(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int remaining = state.getValue(SPREADS_LEFT);
        if (remaining <= 0)
            return;

        // scegli un vicino a caso
        Direction dir = Direction.getRandom(random);
        BlockPos targetPos = pos.relative(dir);
        BlockState targetState = level.getBlockState(targetPos);
        if (!targetState.isAir()) {
            if (!targetState.is(this)) {
                if (Analyzer.canBurn(targetState, level, targetPos)) {
                    // Se infiammabile → fuoco
                    level.setBlock(targetPos, Blocks.FIRE.defaultBlockState(), 16 | 2);
                } else {

                    // Altrimenti trasforma in cenere
                    level.setBlock(targetPos, this.defaultBlockState()
                            .setValue(SPREADS_LEFT, 0), 16 | 2);
                }

                // decrementa i tentativi rimasti
                level.setBlock(pos, state.setValue(SPREADS_LEFT, remaining - 1), 16 | 2);
            }
        }

        // incendia sempre il blocco sotto
        dir = Direction.DOWN;
        targetPos = pos.relative(dir);
        targetState = level.getBlockState(targetPos);
        if (!targetState.isAir() && Analyzer.canBurn(targetState, level, targetPos)) {
            level.setBlock(targetPos, Blocks.FIRE.defaultBlockState(), 16 | 2);
        }

        // Ripianifica il prossimo tick SOLO se ha ancora tentativi
        if (remaining - 1 > 0) {
            level.scheduleTick(pos, this, 40);
        }
    }*/

}