package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.utilities.ApocalypseChunkData;
import com.creatormc.judgementdaymod.utilities.ApocalypseManager;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;

public class ApocalypseChunkGenerator extends ChunkGenerator {
    public static final Codec<ApocalypseChunkGenerator> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ChunkGenerator.CODEC.fieldOf("before").forGetter(g -> g.before),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource))
            .apply(inst, ApocalypseChunkGenerator::new));

    private final ChunkGenerator before;

    private final BlockState ashState = ModBlocks.ASH_BLOCK.get().defaultBlockState();

    public ApocalypseChunkGenerator(ChunkGenerator before, BiomeSource biomeSource) {
        super(biomeSource);
        this.before = before; // delegate to the vanilla generator passed from JSON
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ==== Safe delegations to vanilla ====
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(@Nonnull Executor ex, @Nonnull Blender blender,
            @Nonnull RandomState rs,
            @Nonnull StructureManager sm, @Nonnull ChunkAccess chunk) {
        if (!ApocalypseManager.isApocalypseActive()) {
            return before.fillFromNoise(ex, blender, rs, sm, chunk);
        } else {

            return before.fillFromNoise(ex, blender, rs, sm, chunk).thenApply(chunkAccess -> {
                // Replace all water with air
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (chunk.getBlockState(pos).is(Blocks.WATER)) {
                                chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                            }
                        }
                    }
                }
                return chunkAccess;
            });
        }
    }

    @Override
    public void applyCarvers(@Nonnull WorldGenRegion level, long seed, @Nonnull RandomState rs,
            @Nonnull BiomeManager bm,
            @Nonnull StructureManager sm, @Nonnull ChunkAccess chunk, @Nonnull Carving step) {
        before.applyCarvers(level, seed, rs, bm, sm, chunk, step);
    }

    @Override
    public void spawnOriginalMobs(@Nonnull WorldGenRegion level) {
        before.spawnOriginalMobs(level);
    }

    @Override
    public int getGenDepth() {
        return before.getGenDepth();
    }

    @Override
    public int getSeaLevel() {
        return before.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return before.getMinY();
    }

    @Override
    public int getBaseHeight(int x, int z, @Nonnull Heightmap.Types type, @Nonnull LevelHeightAccessor acc,
            @Nonnull RandomState rs) {
        return before.getBaseHeight(x, z, type, acc, rs);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, @Nonnull LevelHeightAccessor acc, @Nonnull RandomState rs) {
        return before.getBaseColumn(x, z, acc, rs);
    }

    @Override
    public void addDebugScreenInfo(@Nonnull List<String> info, @Nonnull RandomState rs, @Nonnull BlockPos pos) {
        before.addDebugScreenInfo(info, rs, pos);
        info.add("JD apocalypse: " + ("unknown")); // No ServerLevel here; show 'unknown'
    }

    // ==== Surface replacement only when apocalypse is ON ====
    @Override
    public void buildSurface(@Nonnull WorldGenRegion region, @Nonnull StructureManager sm, @Nonnull RandomState rs,
            @Nonnull ChunkAccess chunk) {

        // vanilla surface
        before.buildSurface(region, sm, rs, chunk);
        
        // Register this chunk in the persistent apocalypse data
        ApocalypseChunkData data = ApocalypseChunkData.get(region.getLevel());
        data.setPhase(chunk.getPos(), 0); // Initial phase (unaffected)

        // Skip if apocalypse is not active since terrain will turn to ash later anyway
        if (!ConfigManager.apocalypseActive) {
            return;
        }

        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        final int startX = chunkX << 4;
        final int startZ = chunkZ << 4;

        MutableBlockPos posSurface = new MutableBlockPos();
        MutableBlockPos posUnder1Block = new MutableBlockPos();
        MutableBlockPos posUnder2Blocks = new MutableBlockPos();
        // Column-by-column replacement
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {

                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);

                posSurface.set(startX + lx, y, startZ + lz);
                posUnder1Block.set(startX + lx, y - 1, startZ + lz);
                posUnder2Blocks.set(startX + lx, y - 2, startZ + lz);

                final BlockState cur1 = chunk.getBlockState(posSurface);
                final BlockState cur2 = chunk.getBlockState(posUnder1Block);
                final BlockState cur3 = chunk.getBlockState(posUnder2Blocks);
                if (cur1.isAir() || cur1.is(Blocks.WATER) ||
                        cur2.isAir() || cur2.is(Blocks.WATER) ||
                        cur3.isAir() || cur3.is(Blocks.WATER))
                    continue;

                chunk.setBlockState(posSurface, ashState, false);
                chunk.setBlockState(posUnder1Block, ashState, false);
                chunk.setBlockState(posUnder2Blocks, ashState, false);
            }
        }
    }

    @Override
    public void applyBiomeDecoration(@Nonnull WorldGenLevel wLevel, @Nonnull ChunkAccess chunk,
            @Nonnull StructureManager sm) {
        if (!ApocalypseManager.isApocalypseActive()) {
            before.applyBiomeDecoration(wLevel, chunk, sm);
        } else {
            // TODO Placeholder for custom world elements during apocalypse
        }
    }
}
