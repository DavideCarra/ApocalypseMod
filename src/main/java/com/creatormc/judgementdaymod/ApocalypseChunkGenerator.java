package com.creatormc.judgementdaymod;

import com.creatormc.judgementdaymod.utilities.Analyzer;
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
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ApocalypseChunkGenerator extends ChunkGenerator {
    public static final Codec<ApocalypseChunkGenerator> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ChunkGenerator.CODEC.fieldOf("before").forGetter(g -> g.before),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource))
            .apply(inst, ApocalypseChunkGenerator::new));

    private final ChunkGenerator before;

    private final Random random = new Random();
    private final BlockState ashState = ModBlocks.ASH_BLOCK.get().defaultBlockState();

    public ApocalypseChunkGenerator(ChunkGenerator before, BiomeSource biomeSource) {
        super(biomeSource);
        this.before = before; // delega al generatore vanilla passato dal JSON
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ==== Deleghe "safe" al vanilla ====
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor ex, Blender blender, RandomState rs,
            StructureManager sm, ChunkAccess chunk) {
        if (!ApocalypseManager.isApocalypseActive()) {
            return before.fillFromNoise(ex, blender, rs, sm, chunk);
        } else {

            return before.fillFromNoise(ex, blender, rs, sm, chunk).thenApply(chunkAccess -> {
                // Sostituisci tutta l'acqua con aria
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
    public void applyCarvers(WorldGenRegion level, long seed, RandomState rs, BiomeManager bm,
            StructureManager sm, ChunkAccess chunk, Carving step) {
        before.applyCarvers(level, seed, rs, bm, sm, chunk, step);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
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
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor acc, RandomState rs) {
        return before.getBaseHeight(x, z, type, acc, rs);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor acc, RandomState rs) {
        return before.getBaseColumn(x, z, acc, rs);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState rs, BlockPos pos) {
        before.addDebugScreenInfo(info, rs, pos);
        info.add("JD apocalypse: " + (/* non hai ServerLevel qui; ometti o mostra 'unknown' */ "unknown"));
    }

    // ==== Qui facciamo SOLO la sostituzione topsoil quando ON ====
    @Override
    public void buildSurface(WorldGenRegion region, StructureManager sm, RandomState rs, ChunkAccess chunk) {
        // superficie vanilla
        before.buildSurface(region, sm, rs, chunk);

        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        final int startX = chunkX << 4;
        final int startZ = chunkZ << 4;

        MutableBlockPos posSurface = new MutableBlockPos();
        MutableBlockPos posUnder1Block = new MutableBlockPos();
        MutableBlockPos posUnder2Blocks = new MutableBlockPos();
        // Sostituzione colonna per colonna
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {

                // metto di farlo quando l'apocalisse non è attiva perchè tanto quando è attiva
                // tutto il terreno diventa cenere
                if (!ConfigManager.apocalypseActive && random.nextFloat() > (ConfigManager.apocalypseStage * 0.01f)) {
                    continue;
                }

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
    public void applyBiomeDecoration(WorldGenLevel wLevel, ChunkAccess chunk, StructureManager sm) {
        if (!ApocalypseManager.isApocalypseActive()) {
            before.applyBiomeDecoration(wLevel, chunk, sm);
        } else {
            // valutare se inserire elementi custom nel mondo se l'apocalisse è attiva
        }
    }
}