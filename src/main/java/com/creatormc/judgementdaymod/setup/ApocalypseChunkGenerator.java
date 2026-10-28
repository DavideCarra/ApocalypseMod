package com.creatormc.judgementdaymod.setup;

import com.creatormc.judgementdaymod.utilities.ApocalypseChunkData;
import com.creatormc.judgementdaymod.utilities.ApocalypseManager;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;
import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

public class ApocalypseChunkGenerator extends ChunkGenerator {
    public static final Codec<ApocalypseChunkGenerator> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ChunkGenerator.CODEC.fieldOf("before").forGetter(g -> g.before),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource))
            .apply(inst, ApocalypseChunkGenerator::new));

    private final ChunkGenerator before;

    // Chunk phase data of the level, handed over by the server thread: world generation
    // threads must not look it up in the level data storage (a plain HashMap)
    private volatile ApocalypseChunkData chunkData;

    private final BlockState ashState = ModBlocks.ASH_BLOCK.get().defaultBlockState();

    // Decoration steps that only grow on the surface the apocalypse burns (plants, trees,
    // snow): new chunks skip them once it is active
    private static final int VEGETATION = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
    private static final int TOP_LAYER = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal();

    // Decoration settings of each biome while the apocalypse is active
    private final Map<Holder<Biome>, BiomeGenerationSettings> activeSettings;
    // Their features of each step, ordered and indexed as the decoration does it
    private final Supplier<List<FeatureSorter.StepFeatureData>> activeSteps = Suppliers.memoize(
            () -> FeatureSorter.buildFeaturesPerStep(List.copyOf(getBiomeSource().possibleBiomes()),
                    biome -> activeSettings(biome).features(), true));

    public ApocalypseChunkGenerator(ChunkGenerator before, BiomeSource biomeSource) {
        this(before, biomeSource, new ConcurrentHashMap<>());
    }

    private ApocalypseChunkGenerator(ChunkGenerator before, BiomeSource biomeSource,
            Map<Holder<Biome>, BiomeGenerationSettings> active) {
        super(biomeSource, biome -> active.computeIfAbsent(biome,
                b -> withoutSurfaceDecoration(before.getBiomeGenerationSettings(b))));
        this.before = before; // delegate to the vanilla generator passed from JSON
        this.activeSettings = active;
    }

    /**
     * What a biome generates, as vanilla has it (the settings without the surface are only
     * for the decoration of new chunks while the apocalypse is active).
     */
    @Override
    public BiomeGenerationSettings getBiomeGenerationSettings(Holder<Biome> biome) {
        return before.getBiomeGenerationSettings(biome);
    }

    private BiomeGenerationSettings activeSettings(Holder<Biome> biome) {
        return activeSettings.computeIfAbsent(biome, b -> withoutSurfaceDecoration(before.getBiomeGenerationSettings(b)));
    }

    /**
     * The decoration of a biome, as the generator of the world has it, without the steps
     * that only grow on the surface and without water springs (new chunks hold no water
     * then, see fillFromNoise). The other features keep their order (and so their random
     * seeds): ores and the rest come out as in vanilla.
     */
    private static BiomeGenerationSettings withoutSurfaceDecoration(BiomeGenerationSettings settings) {
        BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
        List<HolderSet<PlacedFeature>> features = settings.features();
        for (int step = 0; step < features.size(); step++) {
            if (step == VEGETATION || step == TOP_LAYER)
                continue;
            for (Holder<PlacedFeature> feature : features.get(step))
                if (!(feature.value().feature().value().config() instanceof SpringConfiguration spring
                        && spring.state.is(FluidTags.WATER)))
                    builder.addFeature(step, feature);
        }
        for (Carving step : settings.getCarvingStages())
            for (Holder<ConfiguredWorldCarver<?>> carver : settings.getCarvers(step))
                builder.addCarver(step, carver);
        return builder.build();
    }

    /** Sorts the features for placeOres now (it takes a while, see OreRetrofit). */
    public void sortFeatures() {
        activeSteps.get();
    }

    /**
     * Places the ores of a chunk generated without them (see OreRetrofit) as its decoration
     * places them: the same features, in the same order, with the same random seeds (see
     * ChunkGenerator#applyBiomeDecoration). The ores of a step keep their order and seeds
     * whatever the later steps hold, so they come out as in a vanilla decoration.
     */
    public void placeOres(WorldGenLevel level, ChunkAccess chunk) {
        final int step = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        final ChunkPos chunkPos = chunk.getPos();
        final SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSection());
        final BlockPos origin = sectionPos.origin();
        final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        final long seed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());

        // The biomes of the chunk and around it, as the decoration takes them
        final Set<Holder<Biome>> biomes = new ObjectArraySet<>();
        ChunkPos.rangeClosed(sectionPos.chunk(), 1).forEach(pos -> {
            for (LevelChunkSection section : level.getChunk(pos.x, pos.z).getSections())
                section.getBiomes().getAll(biomes::add);
        });
        biomes.retainAll(getBiomeSource().possibleBiomes());

        final List<FeatureSorter.StepFeatureData> steps = activeSteps.get();
        if (step >= steps.size())
            return;
        final FeatureSorter.StepFeatureData features = steps.get(step);
        final IntSet indexes = new IntArraySet();
        for (Holder<Biome> biome : biomes) {
            List<HolderSet<PlacedFeature>> perStep = activeSettings(biome).features();
            if (step < perStep.size())
                perStep.get(step).forEach(feature -> indexes.add(features.indexMapping().applyAsInt(feature.value())));
        }
        final int[] sorted = indexes.toIntArray();
        Arrays.sort(sorted);
        for (int index : sorted) {
            random.setFeatureSeed(seed, index, step);
            features.features().get(index).placeWithBiomeCheck(level, this, random, origin);
        }
    }

    public void setChunkData(ApocalypseChunkData data) {
        this.chunkData = data;
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
                replaceWaterWithAir(chunk);
                return chunkAccess;
            });
        }
    }

    /**
     * Replaces every water block of the chunk with air.
     */
    static void replaceWaterWithAir(ChunkAccess chunk) {
        // Sections whose palette has no water can't contain any: skip them. The others are
        // written directly (the chunk is still being generated, by this thread only), then
        // the heightmaps the chunk keeps at this status are worked out again, as the writes
        // through the chunk would have left them.
        final BlockState air = Blocks.AIR.defaultBlockState();
        boolean removed = false;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || !section.maybeHas(state -> state.is(Blocks.WATER)))
                continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).is(Blocks.WATER)) {
                            section.setBlockState(x, y, z, air, false);
                            removed = true;
                        }
                    }
                }
            }
        }
        if (removed) {
            Heightmap.primeHeightmaps(chunk, chunk.getStatus().heightmapsAfter());
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
        ApocalypseChunkData data = chunkData;
        if (data == null) {
            data = ApocalypseChunkData.get(region.getLevel());
        }
        data.setPhase(chunk.getPos(), Generator.stepForNewChunk()); // Initial phase

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
            // Structures and every feature but the surface ones (see withoutSurfaceDecoration),
            // then the water some structures hold (ocean monuments, ruins, shipwrecks)
            super.applyBiomeDecoration(wLevel, chunk, sm);
            replaceWaterWithAir(chunk);
        }
    }
}
