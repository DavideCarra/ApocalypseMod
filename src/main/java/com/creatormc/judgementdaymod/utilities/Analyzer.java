package com.creatormc.judgementdaymod.utilities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;

import java.util.concurrent.ConcurrentHashMap;

import com.creatormc.judgementdaymod.setup.ModBlocks;

public class Analyzer {

    // Cache for canBurn results - performance boost
    private static final ConcurrentHashMap<Block, Boolean> burnCache = new ConcurrentHashMap<>(4096);

    // Static direction array to avoid allocations
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Optimized version with caching
     */
    public static boolean canBurn(BlockState state, BlockGetter level, BlockPos pos) {
        if (state == null)
            return false;

        Block block = state.getBlock();

        // Check cache first - much faster
        Boolean cached = burnCache.get(block);
        if (cached != null)
            return cached;

        boolean canBurn = false;

        // Full directional check
        for (Direction dir : DIRECTIONS) {
            if (state.getFlammability(level, pos, dir) > 0 &&
                    block.getFireSpreadSpeed(state, level, pos, dir) > 0) {
                canBurn = true;
                break;
            }
        }

        // Save in cache (max 4096 entries to avoid memory leaks)
        if (burnCache.size() < 4096) {
            burnCache.put(block, canBurn);
        }

        return canBurn;
    }

    /**
     * Checks if a block should be skipped when finding the surface block.
     * Includes: ash, fire, air (all types), light blocks.
     * Used to go below temporary/empty blocks and find the first solid block.
     */
    public static boolean isSkippable(BlockState blockState) {
        if (blockState == null)
            return false;

        // Temporary or "ghost" blocks (light blocks for lighting updates)
        if (blockState.is(Blocks.LIGHT) || blockState.getBlock() instanceof LightBlock) {
            return true;
        }

        // Ash (custom apocalypse block)
        Block ashBlock = ModBlocks.ASH_BLOCK.get();
        if (blockState.is(ashBlock) || blockState.is(Blocks.BARRIER)) {
            return true;
        }

        // Fire (normal and soul fire)
        if (blockState.is(Blocks.FIRE) || blockState.is(Blocks.SOUL_FIRE)) {
            return true;
        }

        // Air (all variants)
        if (blockState.isAir()
                || blockState.is(Blocks.AIR)
                || blockState.is(Blocks.CAVE_AIR)
                || blockState.is(Blocks.VOID_AIR)) {
            return true;
        }

        return false;
    }

    /**
     * Inline optimized version - faster for frequent checks
     */
    public static boolean isEvaporable(BlockState blockState) {
        if (blockState == null)
            return false;

        // Common quick checks first
        if (blockState.is(Blocks.WATER)) {
            return true;
        }

        // Ice/snow blocks
        if (blockState.is(Blocks.ICE)
                || blockState.is(Blocks.PACKED_ICE)
                || blockState.is(Blocks.BLUE_ICE)
                || blockState.is(Blocks.FROSTED_ICE)
                || blockState.is(Blocks.SNOW)
                || blockState.is(Blocks.SNOW_BLOCK)
                || blockState.is(Blocks.BARRIER)
                || blockState.is(Blocks.LILY_PAD)
                || blockState.getBlock() instanceof LightBlock) {
            return true;
        }
        if (blockState.hasProperty(BlockStateProperties.WATERLOGGED)
                && Boolean.TRUE.equals(blockState.getValue(BlockStateProperties.WATERLOGGED))) {
            return true;
        }

        // Fluid check only if needed
        FluidState fluid = blockState.getFluidState();
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER);
    }

    /**
     * Optimized inline version
     */
    public static boolean isMeldable(BlockState blockState, int apocalypseStage) {
        // Common case first (short-circuit)
        return apocalypseStage >= 100 && !blockState.is(ModBlocks.ASH_BLOCK.get());
    }

    /**
     * Clears the cache periodically to prevent stale data
     */
    public static void clearCache() {
        burnCache.clear();
    }
}
