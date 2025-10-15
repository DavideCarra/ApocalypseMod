package com.creatormc.judgementdaymod.utilities;

import com.creatormc.judgementdaymod.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;

import java.util.concurrent.ConcurrentHashMap;

public class Analyzer {

    // Cache per i risultati di canBurn - ENORME boost di performance
    private static final ConcurrentHashMap<Block, Boolean> burnCache = new ConcurrentHashMap<>(4096);

    // Array statico per evitare allocazioni
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Versione ottimizzata con caching
     */
    public static boolean canBurn(BlockState state, BlockGetter level, BlockPos pos) {
        if (state == null)
            return false;

        Block block = state.getBlock();

        // Check cache first - MOLTO più veloce
        Boolean cached = burnCache.get(block);
        if (cached != null)
            return cached;

        // Solo se non in cache, calcola
        boolean canBurn = false;

        // Check completo con direzioni
        for (Direction dir : DIRECTIONS) {
            if (state.getFlammability(level, pos, dir) > 0 &&
                    block.getFireSpreadSpeed(state, level, pos, dir) > 0) {
                canBurn = true;
                break;

            }
        }

        // Salva in cache (max 256 entries per evitare memory leak)
        if (burnCache.size() < 4096) {
            burnCache.put(block, canBurn);
        }

        return canBurn;
    }

    /**
     * Versione inline ottimizzata - più veloce per check frequenti
     */
    public static boolean isEvaporable(BlockState blockState) {
        if (blockState == null)
            return false;

        // Check diretto sui blocchi più comuni prima
        if (blockState.is(Blocks.WATER)) {
            return true;
        }

        // blocchi di ghiaccio / neve
        if (blockState.is(Blocks.ICE)
                || blockState.is(Blocks.PACKED_ICE)
                || blockState.is(Blocks.BLUE_ICE)
                || blockState.is(Blocks.FROSTED_ICE)
                || blockState.is(Blocks.SNOW) // strato di neve
                || blockState.is(Blocks.SNOW_BLOCK)
                || blockState.is(Blocks.BARRIER)
                || blockState.is(Blocks.CAVE_AIR)
                || blockState.is(Blocks.VOID_AIR)
                || blockState.is(Blocks.AIR)
                || blockState.getBlock() instanceof LightBlock
                ) {
            return true;
        }

        // Check fluidi solo se necessario
        FluidState fluid = blockState.getFluidState();
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER);
    }

    /**
     * Versione ottimizzata con check inline
     */
    public static boolean isMeldable(BlockState blockState, int apocalypseStage) {
        // Check più comune prima (short-circuit evaluation)
        return apocalypseStage >= 100 && !blockState.is(ModBlocks.ASH_BLOCK.get());
    }

    /**
     * Metodo per pulire la cache se necessario (chiamare periodicamente)
     */
    public static void clearCache() {
        burnCache.clear();
    }
}