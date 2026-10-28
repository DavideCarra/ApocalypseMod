package com.creatormc.judgementdaymod.selftest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.utilities.ApocalypseChunkData;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.utilities.Generator;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Test harness (not part of the mod). Runs only when selftest.properties exists in
 * the game directory. Compiles against both the old and the new mod code.
 */
@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SelfTestCommon {

    static Properties props;
    static final List<String> out = new ArrayList<>();
    static MinecraftServer server;
    static int tick = 0;
    static int stage = 0;
    static List<int[]> regions = new ArrayList<>();
    static int waitUntil = 0;

    static boolean enabled() {
        if (props == null) {
            props = new Properties();
            Path p = FMLPaths.GAMEDIR.get().resolve("selftest.properties");
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return props.getProperty("scenario") != null;
    }

    static String scenario() {
        return props.getProperty("scenario");
    }

    // Falling block entities created during the run
    static int fallingJoined;
    static final java.util.Map<String, Integer> fallingTypes = new java.util.TreeMap<>();
    static final List<String> fallingWhere = new ArrayList<>();

    @SubscribeEvent
    public static void onFallingJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent e) {
        if (e.getEntity() instanceof FallingBlockEntity f && !e.getLevel().isClientSide) {
            fallingJoined++;
            int y = f.blockPosition().getY();
            fallingTypes.merge(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(f.getBlockState().getBlock()).getPath()
                    + "@" + (y < 0 ? "below0" : y < 40 ? "0-39" : y < 60 ? "40-59" : y < 80 ? "60-79" : "80+"), 1, Integer::sum);
            if (fallingWhere.size() < 6)
                fallingWhere.add(f.blockPosition().toShortString() + " " + f.getBlockState().getBlock().getDescriptionId()
                        + " below=" + e.getLevel().getBlockState(f.blockPosition().below()).getBlock().getDescriptionId()
                        + " stack=" + java.util.Arrays.stream(Thread.currentThread().getStackTrace()).skip(3).limit(14)
                                .map(el -> el.getClassName().substring(el.getClassName().lastIndexOf('.') + 1) + "." + el.getMethodName())
                                .collect(java.util.stream.Collectors.joining("<")));
        }
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!enabled())
            return;
        server = event.getServer();
        log("START scenario=" + scenario());
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (server == null || event.phase != TickEvent.Phase.END)
            return;
        tick++;
        try {
            if (scenario().equals("clientlight") || scenario().equals("perf")) {
                clientLightTick();
            } else if (scenario().startsWith("compare")) {
                compareTick();
            } else if (scenario().equals("generate")) {
                generateTick();
            } else if (scenario().equals("worldgen")) {
                worldgenTick();
            } else if (scenario().equals("retrofit")) {
                retrofitTick();
            } else if (scenario().equals("watercheck")) {
                // Off the server thread: the noise asks the level for the structures around
                // (Beardifier), which the server thread serves meanwhile
                if (tick == 20) {
                    checkThread = new Thread(() -> {
                        try {
                            waterCheck();
                        } catch (Throwable t) {
                            log("EXCEPTION " + t);
                            for (StackTraceElement el : t.getStackTrace())
                                log("   at " + el);
                        }
                    }, "selftest-water");
                    checkThread.start();
                } else if (tick > 20 && checkThread != null && !checkThread.isAlive()) {
                    finish();
                }
            }
        } catch (Throwable t) {
            log("EXCEPTION " + t);
            for (StackTraceElement el : t.getStackTrace())
                log("   at " + el);
            finish();
        }
        afterHarnessNanos = System.nanoTime();
    }

    // ---------------------------------------------------------------- water removal

    /**
     * The water removal of new chunks against the code it replaced: the same new chunks
     * (vanilla noise, oceans and land) go through both, the blocks and the heightmaps must
     * come out the same. Also at the status of the decoration (after the carvers).
     */
    static Thread checkThread;

    static void waterCheck() throws Exception {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        net.minecraft.world.level.chunk.ChunkGenerator wrapper = level.getChunkSource().getGenerator();
        java.lang.reflect.Field beforeField = wrapper.getClass().getDeclaredField("before");
        beforeField.setAccessible(true);
        net.minecraft.world.level.chunk.ChunkGenerator vanilla = (net.minecraft.world.level.chunk.ChunkGenerator) beforeField.get(wrapper);
        java.lang.reflect.Method replace = wrapper.getClass().getDeclaredMethod("replaceWaterWithAir",
                net.minecraft.world.level.chunk.ChunkAccess.class);
        replace.setAccessible(true);
        net.minecraft.world.level.levelgen.RandomState random = level.getChunkSource().randomState();
        net.minecraft.core.Registry<Biome> biomes = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        int gx = Integer.parseInt(props.getProperty("genX", "3000")), gz = Integer.parseInt(props.getProperty("genZ", "3000"));
        int wantOcean = Integer.parseInt(props.getProperty("ocean", "80")), wantLand = Integer.parseInt(props.getProperty("land", "40"));
        List<ChunkPos> picked = new ArrayList<>();
        int ocean = 0, land = 0;
        for (int i = 0; i < 4000 && (ocean < wantOcean || land < wantLand); i++) {
            ChunkPos cp = new ChunkPos(gx + (i % 64) * 3, gz + (i / 64) * 3);
            boolean isOcean = vanilla.getBiomeSource().getNoiseBiome(cp.getMiddleBlockX() >> 2, 15, cp.getMiddleBlockZ() >> 2,
                    random.sampler()).is(BiomeTags.IS_OCEAN);
            if (isOcean && ocean < wantOcean) {
                ocean++;
                picked.add(cp);
            } else if (!isOcean && land < wantLand) {
                land++;
                picked.add(cp);
            }
        }
        long oldNs = 0, newNs = 0, water = 0;
        int diffs = 0, checked = 0;
        for (ChunkPos cp : picked) {
            for (boolean decorating : new boolean[] { false, true }) {
                net.minecraft.world.level.chunk.ProtoChunk a = newChunk(level, vanilla, random, biomes, cp, decorating);
                net.minecraft.world.level.chunk.ProtoChunk b = newChunk(level, vanilla, random, biomes, cp, decorating);
                for (LevelChunkSection sec : a.getSections())
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++)
                                if (sec.getBlockState(x, y, z).is(Blocks.WATER))
                                    water++;
                long t0 = System.nanoTime();
                oldReplaceWaterWithAir(a);
                long t1 = System.nanoTime();
                replace.invoke(null, b);
                long t2 = System.nanoTime();
                oldNs += t1 - t0;
                newNs += t2 - t1;
                checked++;
                String diff = compareChunks(a, b);
                if (diff != null) {
                    diffs++;
                    if (diffs <= 5)
                        log("WATERDIFF " + cp + " decorating=" + decorating + " " + diff);
                }
            }
        }
        log(String.format(java.util.Locale.ROOT, "WATERCHECK chunks=%d ocean=%d land=%d water=%d diffs=%d oldMs=%.1f newMs=%.1f", checked, ocean, land,
                water, diffs, oldNs / 1e6, newNs / 1e6));
    }

    static net.minecraft.world.level.chunk.ProtoChunk newChunk(ServerLevel level, net.minecraft.world.level.chunk.ChunkGenerator vanilla,
            net.minecraft.world.level.levelgen.RandomState random, net.minecraft.core.Registry<Biome> biomes, ChunkPos cp, boolean decorating) {
        net.minecraft.world.level.chunk.ProtoChunk c = new net.minecraft.world.level.chunk.ProtoChunk(cp,
                net.minecraft.world.level.chunk.UpgradeData.EMPTY, level, biomes, null);
        net.minecraft.world.level.levelgen.blending.Blender blender = net.minecraft.world.level.levelgen.blending.Blender.empty();
        vanilla.createBiomes(net.minecraft.Util.backgroundExecutor(), random, blender, level.structureManager(), c).join();
        vanilla.fillFromNoise(net.minecraft.Util.backgroundExecutor(), blender, random, level.structureManager(), c).join();
        if (decorating) {
            // As when the decoration runs: after the carvers, with the final heightmaps
            c.setStatus(net.minecraft.world.level.chunk.ChunkStatus.CARVERS);
            Heightmap.primeHeightmaps(c, net.minecraft.world.level.chunk.ChunkStatus.CARVERS.heightmapsAfter());
        }
        return c;
    }

    /** The water removal as it was before (1.3.2). */
    static void oldReplaceWaterWithAir(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        final LevelChunkSection[] sections = chunk.getSections();
        final boolean[] sectionMayHaveWater = new boolean[sections.length];
        for (int i = 0; i < sections.length; i++) {
            sectionMayHaveWater[i] = sections[i] != null && sections[i].maybeHas(state -> state.is(Blocks.WATER));
        }
        final int minSection = chunk.getMinSection();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int i = 0; i < sections.length; i++) {
                    if (!sectionMayHaveWater[i])
                        continue;
                    final int sectionMinY = (minSection + i) << 4;
                    for (int y = sectionMinY; y < sectionMinY + 16; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (chunk.getBlockState(pos).is(Blocks.WATER)) {
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                        }
                    }
                }
            }
        }
    }

    /** null if both chunks hold the same blocks and heightmaps. */
    static String compareChunks(net.minecraft.world.level.chunk.ChunkAccess a, net.minecraft.world.level.chunk.ChunkAccess b) {
        for (int i = 0; i < a.getSections().length; i++) {
            LevelChunkSection sa = a.getSections()[i], sb = b.getSections()[i];
            if (sa.hasOnlyAir() != sb.hasOnlyAir())
                return "section " + i + " onlyAir " + sa.hasOnlyAir() + "/" + sb.hasOnlyAir();
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (sa.getBlockState(x, y, z) != sb.getBlockState(x, y, z))
                            return "block " + x + "," + ((a.getMinSection() + i) * 16 + y) + "," + z + " " + sa.getBlockState(x, y, z) + "/"
                                    + sb.getBlockState(x, y, z);
        }
        for (Heightmap.Types type : Heightmap.Types.values()) {
            boolean ha = a.hasPrimedHeightmap(type), hb = b.hasPrimedHeightmap(type);
            if (ha != hb)
                return "heightmap " + type + " present " + ha + "/" + hb;
            if (ha && !java.util.Arrays.equals(a.getOrCreateHeightmapUnprimed(type).getRawData(),
                    b.getOrCreateHeightmapUnprimed(type).getRawData()))
                return "heightmap " + type + " differs";
        }
        return null;
    }

    // ---------------------------------------------------------------- worldgen

    static int genStage, genTick;
    static long genStartNanos;
    static final List<int[]> genChunks = new ArrayList<>();

    /**
     * Generates size x size new chunks at (genX, genZ) (chunk coordinates) and prints the time
     * and CPU it took, then the mod's work on them and what they hold.
     */
    static void worldgenTick() throws Exception {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (genStage == 0 && tick >= 20) {
            freezeWorld(level);
            configure();
            ConfigManager.apocalypseActive = !"false".equals(props.getProperty("active", "true"));
            int n = Integer.parseInt(props.getProperty("size", "20"));
            int gx = Integer.parseInt(props.getProperty("genX", "2000"));
            int gz = Integer.parseInt(props.getProperty("genZ", "2000"));
            startWindow("gen");
            genStartNanos = System.nanoTime();
            for (int x = gx; x < gx + n; x++)
                for (int z = gz; z < gz + n; z++) {
                    genChunks.add(new int[] { x, z });
                    level.setChunkForced(x, z, true);
                }
            genTick = tick;
            genStage = 1;
        } else if (genStage == 1) {
            int loaded = 0;
            for (int[] c : genChunks)
                if (level.getChunkSource().getChunkNow(c[0], c[1]) != null)
                    loaded++;
            if (loaded == genChunks.size() || tick - genTick > 6000) {
                log(String.format(java.util.Locale.ROOT, "GEN chunks=%d loaded=%d ms=%.0f ticks=%d", genChunks.size(), loaded,
                        (System.nanoTime() - genStartNanos) / 1e6, tick - genTick));
                endWindow();
                startWindow("process");
                if ("true".equals(props.getProperty("sample")))
                    startSampler(Thread.currentThread());
                genTick = tick;
                genStage = 2;
            }
        } else if (genStage == 2 && tick - genTick >= Integer.parseInt(props.getProperty("processTicks", "600"))) {
            endWindow();
            stopSampler();
            long ores = 0, logs = 0, leaves = 0, spawners = 0, amethyst = 0, chests = 0, lava = 0;
            java.util.Map<String, Integer> starts = new java.util.TreeMap<>();
            java.util.Map<String, Integer> oreTypes = new java.util.TreeMap<>();
            net.minecraft.core.Registry<net.minecraft.world.level.levelgen.structure.Structure> structures = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            for (int[] c : genChunks) {
                LevelChunk chunk = level.getChunk(c[0], c[1]);
                chunk.getAllStarts().forEach((structure, start) -> {
                    if (start.isValid())
                        starts.merge(String.valueOf(structures.getKey(structure)), 1, Integer::sum);
                });
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section.hasOnlyAir())
                        continue;
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState st = section.getBlockState(x, y, z);
                                if (st.is(net.minecraftforge.common.Tags.Blocks.ORES)) {
                                    ores++;
                                    oreTypes.merge(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock()).getPath(), 1,
                                            Integer::sum);
                                } else if (st.is(net.minecraft.tags.BlockTags.LOGS))
                                    logs++;
                                else if (st.is(net.minecraft.tags.BlockTags.LEAVES))
                                    leaves++;
                                else if (st.is(Blocks.SPAWNER))
                                    spawners++;
                                else if (st.is(Blocks.AMETHYST_BLOCK) || st.is(Blocks.BUDDING_AMETHYST))
                                    amethyst++;
                                else if (st.is(Blocks.CHEST))
                                    chests++;
                                else if (st.is(Blocks.LAVA))
                                    lava++;
                            }
                }
            }
            log("GENCOUNT ores=" + ores + " logs=" + logs + " leaves=" + leaves + " spawners=" + spawners + " amethyst=" + amethyst
                    + " chests=" + chests + " lava=" + lava + " starts=" + starts);
            log("GENORES " + oreTypes);
            log("GENRETROFIT " + ApocalypseChunkData.get(level).oresRetrofit());
            if ("true".equals(props.getProperty("dumpOres")))
                dumpOres(level, "dump");
            finish();
        }
    }

    // Main thread stack samples: inclusive count of every method seen in a sample
    static volatile boolean sampling;
    static final java.util.Map<String, Integer> samples = new java.util.HashMap<>();
    static int sampleCount;

    static void startSampler(Thread target) {
        sampling = true;
        Thread t = new Thread(() -> {
            while (sampling) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (StackTraceElement e : target.getStackTrace())
                    seen.add(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName());
                synchronized (samples) {
                    sampleCount++;
                    for (String m : seen)
                        samples.merge(m, 1, Integer::sum);
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "selftest-sampler");
        t.setDaemon(true);
        t.start();
    }

    static void stopSampler() {
        if (!sampling)
            return;
        sampling = false;
        synchronized (samples) {
            log("SAMPLES total=" + sampleCount);
            samples.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(70)
                    .forEach(e -> log(String.format(java.util.Locale.ROOT, "SAMPLE %5.1f%% %s", 100.0 * e.getValue() / sampleCount, e.getKey())));
        }
    }

    // ---------------------------------------------------------------- ore retrofit

    /**
     * Chunks an older version generated without ores (genX, genZ, size: the forced area of
     * that world) are seen again: their ores must come back, except in the chunks players
     * stayed around (touchX, touchZ: 3x3 chunks given a long inhabited time) or left
     * something in (a chest at the centre of chestX, chestZ).
     */
    static void retrofitTick() throws Exception {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        int n = Integer.parseInt(props.getProperty("size", "24"));
        int gx = Integer.parseInt(props.getProperty("genX", "2000"));
        int gz = Integer.parseInt(props.getProperty("genZ", "2000"));
        int tx = Integer.parseInt(props.getProperty("touchX", String.valueOf(gx + 6)));
        int tz = Integer.parseInt(props.getProperty("touchZ", String.valueOf(gz + 6)));
        int cx = Integer.parseInt(props.getProperty("chestX", String.valueOf(gx + 15)));
        int cz = Integer.parseInt(props.getProperty("chestZ", String.valueOf(gz + 15)));
        if (genStage == 0 && tick >= 20) {
            freezeWorld(level);
            configure();
            ConfigManager.apocalypseActive = !"false".equals(props.getProperty("active", "true"));
            for (int x = gx; x < gx + n; x++)
                for (int z = gz; z < gz + n; z++) {
                    genChunks.add(new int[] { x, z });
                    level.setChunkForced(x, z, true);
                }
            genTick = tick;
            genStage = 1;
        } else if (genStage == 1) {
            for (int[] c : genChunks)
                if (level.getChunkSource().getChunkNow(c[0], c[1]) == null)
                    return;
            for (int x = tx; x < tx + 3; x++)
                for (int z = tz; z < tz + 3; z++)
                    level.getChunk(x, z).setInhabitedTime(20 * 60 * 20);
            // Underground: the surface burns
            level.setBlock(new BlockPos((cx << 4) + 8, 10, (cz << 4) + 8), Blocks.CHEST.defaultBlockState(), 3);
            log("RETRO retrofit=" + ApocalypseChunkData.get(level).oresRetrofit());
            log("RETRO before " + oreCounts(level, "inner") + " touched=" + oreCounts(level, "touched") + " chest="
                    + oreCounts(level, "chest"));
            startWindow("retrofit");
            if ("true".equals(props.getProperty("sample")))
                startSampler(Thread.currentThread());
            // only=even: half of the chunks (as a checkerboard), the others are seen later
            boolean even = "even".equals(props.getProperty("only"));
            for (int[] c : genChunks)
                if (!even || ((c[0] + c[1]) & 1) == 0)
                    com.creatormc.judgementdaymod.handlers.DayTracker.enqueueChunk(new ChunkToProcess(level, c[0], c[1]));
            genTick = tick;
            genStage = 2;
        } else if (genStage == 2 && tick - genTick >= Integer.parseInt(props.getProperty("processTicks", "600"))) {
            endWindow();
            stopSampler();
            log("RETRO after " + oreCounts(level, "inner") + " touched=" + oreCounts(level, "touched") + " chest="
                    + oreCounts(level, "chest"));
            dumpOres(level, "dump");
            finish();
        }
    }

    /** The chunks of a part of the test area: inner (2 chunks from its sides), touched, chest. */
    static List<int[]> area(String part) {
        int n = Integer.parseInt(props.getProperty("size", "24"));
        int gx = Integer.parseInt(props.getProperty("genX", "2000"));
        int gz = Integer.parseInt(props.getProperty("genZ", "2000"));
        List<int[]> chunks = new ArrayList<>();
        if (part.equals("touched")) {
            int tx = Integer.parseInt(props.getProperty("touchX", String.valueOf(gx + 6)));
            int tz = Integer.parseInt(props.getProperty("touchZ", String.valueOf(gz + 6)));
            for (int x = tx; x < tx + 3; x++)
                for (int z = tz; z < tz + 3; z++)
                    chunks.add(new int[] { x, z });
        } else if (part.equals("chest")) {
            chunks.add(new int[] { Integer.parseInt(props.getProperty("chestX", String.valueOf(gx + 15))),
                    Integer.parseInt(props.getProperty("chestZ", String.valueOf(gz + 15))) });
        } else {
            int m = Integer.parseInt(props.getProperty("margin", "2"));
            for (int x = gx + m; x < gx + n - m; x++)
                for (int z = gz + m; z < gz + n - m; z++)
                    chunks.add(new int[] { x, z });
        }
        return chunks;
    }

    /** Decoration ores (and all ores) in a part of the area. */
    static String oreCounts(ServerLevel level, String part) {
        long ores = 0, decoration = 0;
        for (int[] c : area(part)) {
            LevelChunk chunk = level.getChunk(c[0], c[1]);
            for (LevelChunkSection section : chunk.getSections()) {
                if (section.hasOnlyAir())
                    continue;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            BlockState st = section.getBlockState(x, y, z);
                            if (st.is(net.minecraftforge.common.Tags.Blocks.ORES)) {
                                ores++;
                                if (!st.is(Blocks.COPPER_ORE) && !st.is(Blocks.DEEPSLATE_IRON_ORE))
                                    decoration++;
                            }
                        }
            }
        }
        return part + "{ores=" + ores + " decorationOres=" + decoration + "}";
    }

    /** Every ore block of the inner area, to compare two runs (file <output>.ores). */
    static void dumpOres(ServerLevel level, String tag) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int[] c : area("inner")) {
            LevelChunk chunk = level.getChunk(c[0], c[1]);
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (sections[i].hasOnlyAir())
                    continue;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            BlockState st = sections[i].getBlockState(x, y, z);
                            if (st.is(net.minecraftforge.common.Tags.Blocks.ORES))
                                lines.add(((c[0] << 4) + x) + " " + ((chunk.getMinSection() + i) * 16 + y) + " " + ((c[1] << 4) + z) + " "
                                        + net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock()).getPath());
                        }
            }
        }
        Files.write(FMLPaths.GAMEDIR.get().resolve(props.getProperty("output", "selftest-out.txt") + ".ores"), lines);
        log("DUMPED " + tag + " ores=" + lines.size());
    }

    // ---------------------------------------------------------------- perf

    static long tickStartNanos, harnessNanos, afterHarnessNanos;
    static String perfWindow;
    static final java.util.Map<String, List<long[]>> perfTicks = new java.util.LinkedHashMap<>();
    static java.util.Map<Long, long[]> cpuStart;
    static final java.util.Map<String, long[]> pktStats = new java.util.TreeMap<>();
    static final net.minecraft.network.FriendlyByteBuf sizeBuf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onTickFirst(TickEvent.ServerTickEvent event) {
        if (server != null && event.phase == TickEvent.Phase.START) {
            tickStartNanos = System.nanoTime();
            harnessNanos = 0;
            afterHarnessNanos = 0;
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onTickLast(TickEvent.ServerTickEvent event) {
        if (server == null || event.phase != TickEvent.Phase.END || perfWindow == null)
            return;
        long now = System.nanoTime();
        long total = now - tickStartNanos - harnessNanos;
        long mod = afterHarnessNanos > 0 ? now - afterHarnessNanos : 0;
        perfTicks.computeIfAbsent(perfWindow, k -> new ArrayList<>()).add(new long[] { total, mod });
    }

    static java.util.Map<Long, long[]> cpuSnapshot() {
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        java.util.Map<Long, long[]> m = new java.util.HashMap<>();
        for (long id : mx.getAllThreadIds()) {
            long cpu = mx.getThreadCpuTime(id);
            long alloc = mx instanceof com.sun.management.ThreadMXBean s ? s.getThreadAllocatedBytes(id) : 0;
            m.put(id, new long[] { cpu, alloc });
        }
        return m;
    }

    static String threadGroup(long id) {
        java.lang.management.ThreadInfo ti = java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(id);
        if (ti == null)
            return "gone";
        String n = ti.getThreadName();
        if (n.equals("Server thread"))
            return "main";
        if (n.startsWith("Worker-Main"))
            return "worker";
        if (n.startsWith("IO-Worker"))
            return "io";
        return "other";
    }

    static void startWindow(String name) {
        perfWindow = name;
        cpuStart = cpuSnapshot();
        pktStats.clear();
    }

    static void endWindow() {
        if (perfWindow == null)
            return;
        java.util.Map<Long, long[]> end = cpuSnapshot();
        java.util.Map<String, long[]> groups = new java.util.TreeMap<>();
        for (java.util.Map.Entry<Long, long[]> e : end.entrySet()) {
            long[] s = cpuStart.getOrDefault(e.getKey(), new long[] { 0, 0 });
            long[] g = groups.computeIfAbsent(threadGroup(e.getKey()), k -> new long[2]);
            g[0] += Math.max(0, e.getValue()[0] - s[0]);
            g[1] += Math.max(0, e.getValue()[1] - s[1]);
        }
        List<long[]> ticks = perfTicks.getOrDefault(perfWindow, new ArrayList<>());
        long[] totals = new long[ticks.size()];
        long sum = 0, modSum = 0, modMax = 0;
        int over50 = 0;
        for (int i = 0; i < ticks.size(); i++) {
            totals[i] = ticks.get(i)[0];
            sum += ticks.get(i)[0];
            modSum += ticks.get(i)[1];
            modMax = Math.max(modMax, ticks.get(i)[1]);
            if (ticks.get(i)[0] > 50_000_000L)
                over50++;
        }
        long[] sorted = totals.clone();
        java.util.Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder("PERF window=" + perfWindow + " ticks=" + ticks.size());
        sb.append(String.format(java.util.Locale.ROOT, " tickTotalMs=%.1f p50=%.2f p95=%.2f max=%.1f over50=%d modEndTotalMs=%.1f modEndMax=%.1f",
                sum / 1e6, pct(sorted, 50), pct(sorted, 95), sorted.length > 0 ? sorted[sorted.length - 1] / 1e6 : 0, over50,
                modSum / 1e6, modMax / 1e6));
        for (java.util.Map.Entry<String, long[]> g : groups.entrySet())
            sb.append(String.format(java.util.Locale.ROOT, " cpu.%s=%.0fms alloc.%s=%.1fMB", g.getKey(), g.getValue()[0] / 1e6,
                    g.getKey(), g.getValue()[1] / 1048576.0));
        for (java.util.Map.Entry<String, long[]> p : pktStats.entrySet())
            sb.append(" pkt." + p.getKey() + "=" + p.getValue()[0] + "/" + (p.getValue()[1] / 1024) + "KB"
                    + (p.getValue().length > 2 ? "/zip" + (p.getValue()[2] / 1024) + "KB" : ""));
        try {
            long[] prof = (long[]) Generator.class.getField("PROF").get(null);
            log(String.format(java.util.Locale.ROOT, "PROF window=%s modMainMs=%.1f processMs=%.1f evapMs=%.1f transformMs=%.1f updateClientsMs=%.1f deferredResendMs=%.2f chunks=%d",
                    perfWindow, (prof[0] + prof[4]) / 1e6, prof[0] / 1e6, prof[5] / 1e6, prof[6] / 1e6, prof[3] / 1e6, prof[4] / 1e6, prof[7]));
            if (prof.length > 8)
                log(String.format(java.util.Locale.ROOT, "PROF2 window=%s transformCpuMs=%.1f evapCpuMs=%.1f lightPartMs=%.1f scheduleMs=%.1f primeMs=%.1f submitMs=%.1f",
                        perfWindow, prof[12] / 1e6, prof[13] / 1e6, prof[8] / 1e6, prof[9] / 1e6, prof[10] / 1e6, prof[11] / 1e6));
            try {
                long[] sent = (long[]) Class.forName("com.creatormc.judgementdaymod.utilities.ChunkLightSync").getField("SENT").get(null);
                log("PROF3 window=" + perfWindow + " modLightPackets=" + sent[0] + " modLightSections=" + sent[1]);
                java.util.Arrays.fill(sent, 0);
            } catch (NoSuchFieldException | ClassNotFoundException e) {
                // not instrumented
            }
            java.util.Arrays.fill(prof, 0);
        } catch (NoSuchFieldException e) {
            // not instrumented
        } catch (Exception e) {
            log("PROF error " + e);
        }
        log(sb.toString());
        // The heaviest ticks of the mod's end of tick work, with their place in the window
        Integer[] order = new Integer[ticks.size()];
        for (int i = 0; i < order.length; i++)
            order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Long.compare(ticks.get(y)[1], ticks.get(x)[1]));
        StringBuilder top = new StringBuilder("PERFTOP window=" + perfWindow);
        for (int i = 0; i < Math.min(8, order.length); i++)
            top.append(String.format(java.util.Locale.ROOT, " t%d=%.1f", order[i], ticks.get(order[i])[1] / 1e6));
        log(top.toString());
        perfWindow = null;
    }

    static double pct(long[] sorted, int p) {
        if (sorted.length == 0)
            return 0;
        int i = Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, i)] / 1e6;
    }

    static void countPacket(Object msg) {
        if (!(msg instanceof net.minecraft.network.protocol.Packet<?> packet))
            return;
        String name = msg.getClass().getSimpleName().replace("Clientbound", "").replace("Packet", "");
        long bytes = 0;
        try {
            sizeBuf.clear();
            packet.write(sizeBuf);
            bytes = sizeBuf.writerIndex();
        } catch (Throwable t) {
            bytes = -1;
        }
        long[] st = pktStats.computeIfAbsent(name, k -> new long[2]);
        st[0]++;
        st[1] += Math.max(0, bytes);
        // Network size on a dedicated server (compression threshold 256, zlib default level)
        long wire = bytes;
        if (bytes >= 256) {
            byte[] raw = new byte[(int) bytes];
            sizeBuf.getBytes(0, raw);
            deflater.reset();
            deflater.setInput(raw);
            deflater.finish();
            wire = 0;
            while (!deflater.finished())
                wire += deflater.deflate(deflateOut);
        }
        long[] tot = pktStats.computeIfAbsent("~TOTAL", k -> new long[3]);
        tot[0]++;
        tot[1] += Math.max(0, bytes);
        tot[2] += Math.max(0, wire);
    }

    static final java.util.zip.Deflater deflater = new java.util.zip.Deflater();
    static final byte[] deflateOut = new byte[65536];

    static void resetChunksAroundPlayers(ServerLevel level) throws Exception {
        try {
            Generator.class.getMethod("resetPlayersChunks", ServerLevel.class).invoke(null, level);
        } catch (NoSuchMethodException e) {
            Generator.class.getMethod("resetPlayerChunks", net.minecraft.server.level.ServerPlayer.class).invoke(null, fakePlayer);
        }
    }

    // ---------------------------------------------------------------- generate

    /** Generates and saves the test areas once, so every run starts from the same files. */
    static void generateTick() {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (stage == 0 && tick >= 20) {
            freezeWorld(level);
            findRegions(level);
            int radius = Integer.parseInt(props.getProperty("loadRadius", "5"));
            for (int[] r : regions) {
                for (int x = r[0] - radius; x <= r[0] + radius; x++)
                    for (int z = r[1] - radius; z <= r[1] + radius; z++) {
                        level.getChunk(x, z);
                    }
            }
            stage = 1;
            waitUntil = tick + 40;
        } else if (stage == 1 && tick >= waitUntil) {
            dumpRegions(level, "GENERATED");
            finish();
        }
    }

    // ---------------------------------------------------------------- client light

    static io.netty.channel.embedded.EmbeddedChannel channel;
    static net.minecraft.server.level.ServerPlayer fakePlayer;
    // client view: chunk -> section index -> light arrays (sky, block)
    static final java.util.Map<Long, byte[][][]> clientLight = new java.util.HashMap<>();
    // chunks touched by block updates (the client relights them itself)
    static final java.util.Set<Long> clientRelit = new java.util.HashSet<>();
    // section -> tick of the last light data / last nearby block update the client got
    static final java.util.Map<Long, Integer> lastLightTick = new java.util.HashMap<>();
    static final java.util.Map<Long, Integer> lastBlockTick = new java.util.HashMap<>();
    static int packetsChunk, packetsLight, packetsForget, packetsBlocks;
    static String[] rapidDays;
    static int rapidIndex;

    static void clientLightTick() throws Exception {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (stage == 0 && tick >= 20) {
            long setup0 = System.nanoTime();
            freezeWorld(level);
            findRegions(level);
            configure();
            ApocalypseChunkData data = ApocalypseChunkData.get(level);
            int savedPhase = Integer.parseInt(props.getProperty("savedPhase", "4"));
            int[] r = regions.get(0);
            for (int x = r[0] - 10; x <= r[0] + 10; x++)
                for (int z = r[1] - 10; z <= r[1] + 10; z++)
                    data.setPhase(new ChunkPos(x, z), savedPhase);

            com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(
                    java.util.UUID.fromString("11111111-2222-3333-4444-555555555555"), "LightTester");
            fakePlayer = new net.minecraft.server.level.ServerPlayer(server, level, profile);
            fakePlayer.moveTo((r[0] << 4) + 8, 200, (r[1] << 4) + 8, 0, 0);
            net.minecraft.network.Connection conn = new net.minecraft.network.Connection(
                    net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(conn);
            harnessNanos += System.nanoTime() - setup0;
            startWindow("join");
            server.getPlayerList().placeNewPlayer(conn, fakePlayer);
            fakePlayer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            log("PLAYER at " + fakePlayer.blockPosition().toShortString());
            stage = 1;
            waitUntil = tick + Integer.parseInt(props.getProperty("lightTicks", "600"));
        } else if (stage == 1) {
            drainPackets();
            if (tick >= waitUntil) {
                String change = props.getProperty("phaseChangeDay");
                if (change != null) {
                    // A new phase starts: lower heights, every chunk around the player again
                    ConfigManager.apocalypseCurrentDay = Integer.parseInt(change);
                    ConfigManager.minWaterEvaporationHeight = Integer.parseInt(props.getProperty("newWaterHeight", "4"));
                    ConfigManager.minDamageHeight = Integer.parseInt(props.getProperty("newDamageHeight", "4"));
                    endWindow();
                    startWindow("phase");
                    resetChunksAroundPlayers(level);
                    log("PHASE CHANGE day=" + change);
                    stage = 2;
                    waitUntil = tick + Integer.parseInt(props.getProperty("lightTicks", "600"));
                } else if (props.getProperty("rapidDays") != null) {
                    // New days one after the other through the real day event (phases,
                    // heights, every chunk around the players again), as a player
                    // skipping days in a row does
                    endWindow();
                    startWindow("phase");
                    rapidDays = props.getProperty("rapidDays").split(",");
                    rapidIndex = 0;
                    stage = 5;
                    waitUntil = tick;
                } else {
                    endWindow();
                    long h0 = System.nanoTime();
                    compareClientLight(level);
                    lightReport(level);
                    mobReport(level);
                    harnessNanos += System.nanoTime() - h0;
                    finish();
                }
            }
        } else if (stage == 5) {
            drainPackets();
            if (tick >= waitUntil) {
                ConfigManager.apocalypseCurrentDay = Integer.parseInt(rapidDays[rapidIndex].trim());
                Generator.handleDayEvent();
                log("DAY " + ConfigManager.apocalypseCurrentDay + " water=" + ConfigManager.minWaterEvaporationHeight
                        + " damage=" + ConfigManager.minDamageHeight);
                rapidIndex++;
                if (rapidIndex >= rapidDays.length) {
                    stage = 2;
                    waitUntil = tick + Integer.parseInt(props.getProperty("lightTicks", "600"));
                } else {
                    waitUntil = tick + Integer.parseInt(props.getProperty("rapidInterval", "20"));
                }
            }
        } else if (stage == 2) {
            drainPackets();
            if (tick >= waitUntil) {
                endWindow();
                long h0 = System.nanoTime();
                compareClientLight(level);
                lightReport(level);
                mobReport(level);
                harnessNanos += System.nanoTime() - h0;
                int idle = Integer.parseInt(props.getProperty("idleTicks", "0"));
                if (idle > 0) {
                    startWindow("idle");
                    stage = 3;
                    waitUntil = tick + idle;
                } else {
                    finish();
                }
            }
        } else if (stage == 3) {
            drainPackets();
            if (tick >= waitUntil) {
                endWindow();
                finish();
            }
        }
    }

    static void drainPackets() {
        long h0 = System.nanoTime();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            handlePacket(msg);
        }
        harnessNanos += System.nanoTime() - h0;
    }

    @SuppressWarnings("unchecked")
    static void handlePacket(Object msg) {
        if (!(msg instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket))
            countPacket(msg);
        if (msg instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
            for (Object sub : bundle.subPackets())
                handlePacket(sub);
        } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket p) {
            packetsChunk++;
            long key = ChunkPos.asLong(p.getX(), p.getZ());
            clientLight.remove(key);
            applyLight(key, p.getLightData());
        } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundLightUpdatePacket p) {
            packetsLight++;
            applyLight(ChunkPos.asLong(p.getX(), p.getZ()), p.getLightData());
        } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket p) {
            packetsForget++;
            long key = ChunkPos.asLong(p.getX(), p.getZ());
            clientLight.remove(key);
        } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket p) {
            packetsBlocks++;
            markRelit(p.getPos());
        } else if (msg instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket p) {
            packetsBlocks++;
            p.runUpdates((pos, state) -> markRelit(pos));
        }
    }

    /** The client relights around a changed block itself: mark the section and its neighbours. */
    static void markRelit(BlockPos pos) {
        int cx = pos.getX() >> 4, cy = pos.getY() >> 4, cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    long k = net.minecraft.core.SectionPos.asLong(cx + dx, cy + dy, cz + dz);
                    clientRelit.add(k);
                    lastBlockTick.put(k, tick);
                }
    }

    static void applyLight(long key, net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        int count = level.getLightEngine().getLightSectionCount();
        int min = level.getLightEngine().getMinLightSection();
        byte[][][] chunk = clientLight.computeIfAbsent(key, k -> new byte[2][count][]);
        applyLayer(chunk[0], count, data.getSkyYMask(), data.getEmptySkyYMask(), data.getSkyUpdates().iterator());
        applyLayer(chunk[1], count, data.getBlockYMask(), data.getEmptyBlockYMask(), data.getBlockUpdates().iterator());
        ChunkPos cp = new ChunkPos(key);
        for (int i = 0; i < count; i++)
            if (data.getSkyYMask().get(i) || data.getEmptySkyYMask().get(i) || data.getBlockYMask().get(i)
                    || data.getEmptyBlockYMask().get(i))
                lastLightTick.put(net.minecraft.core.SectionPos.asLong(cp.x, min + i, cp.z), tick);
    }

    static void applyLayer(byte[][] layer, int count, java.util.BitSet mask, java.util.BitSet empty, java.util.Iterator<byte[]> it) {
        for (int i = 0; i < count; i++) {
            if (mask.get(i))
                layer[i] = it.next().clone();
            else if (empty.get(i))
                layer[i] = new byte[2048];
        }
    }

    static void compareClientLight(ServerLevel level) {
        int min = level.getLightEngine().getMinLightSection();
        int count = level.getLightEngine().getLightSectionCount();
        ChunkPos center = new ChunkPos(fakePlayer.blockPosition());
        int viewRadius = Integer.parseInt(props.getProperty("compareRadius", "3"));
        int sections = 0, badSky = 0, badBlock = 0, badSkyClean = 0, badBlockClean = 0, chunks = 0, missing = 0, relitChunks = 0;
        int unexplained = 0;
        for (int x = center.x - viewRadius; x <= center.x + viewRadius; x++) {
            for (int z = center.z - viewRadius; z <= center.z + viewRadius; z++) {
                long key = ChunkPos.asLong(x, z);
                byte[][][] client = clientLight.get(key);
                if (client == null) {
                    missing++;
                    continue;
                }
                chunks++;
                for (int i = 0; i < count; i++) {
                    net.minecraft.core.SectionPos sp = net.minecraft.core.SectionPos.of(x, min + i, z);
                    boolean relit = clientRelit.contains(sp.asLong());
                    if (relit)
                        relitChunks++;
                    for (int layer = 0; layer < 2; layer++) {
                        net.minecraft.world.level.chunk.DataLayer server = level.getLightEngine()
                                .getLayerListener(layer == 0 ? LightLayer.SKY : LightLayer.BLOCK).getDataLayerData(sp);
                        if (server == null)
                            continue;
                        sections++;
                        byte[] s = server.getData();
                        byte[] c = client[layer][i];
                        boolean same = c != null && java.util.Arrays.equals(s, c);
                        if (!same) {
                            Integer lt = lastLightTick.get(sp.asLong());
                            Integer bt = lastBlockTick.get(sp.asLong());
                            boolean explained = bt != null && (lt == null || bt >= lt);
                            if (!explained) {
                                unexplained++;
                                int diff = 0, maxd = 0;
                                for (int b = 0; b < 2048; b++) {
                                    int sv = s[b] & 0xff, cv = c == null ? 0 : c[b] & 0xff;
                                    if (sv != cv) {
                                        diff++;
                                        maxd = Math.max(maxd, Math.max(Math.abs((sv & 15) - (cv & 15)), Math.abs((sv >> 4) - (cv >> 4))));
                                    }
                                }
                                boolean edge = level.getChunkSource().chunkMap.getPlayers(new ChunkPos(x, z), true).contains(fakePlayer);
                                if (unexplained <= 10)
                                    log("UNEXPLAINED " + (layer == 0 ? "sky" : "block") + " " + x + " " + (min + i) + " " + z + " edge=" + edge
                                            + " lastLight=" + lt + " lastBlock=" + bt + " now=" + tick + " diffBytes=" + diff + " maxDelta=" + maxd
                                            + " clientNull=" + (c == null));
                            }
                            if (layer == 0) {
                                badSky++;
                                if (!relit)
                                    badSkyClean++;
                            } else {
                                badBlock++;
                                if (!relit)
                                    badBlockClean++;
                            }
                        }
                    }
                }
            }
        }
        log("CLIENTLIGHT chunks=" + chunks + " missing=" + missing + " relitSections=" + relitChunks + " sections=" + sections
                + " badSky=" + badSky + " badBlock=" + badBlock + " badSkyClean=" + badSkyClean + " badBlockClean=" + badBlockClean + " UNEXPLAINED=" + unexplained
                + " packets chunk=" + packetsChunk + " light=" + packetsLight + " forget=" + packetsForget + " blocks=" + packetsBlocks);
    }

    // ---------------------------------------------------------------- compare

    static void compareTick() {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (stage == 0 && tick >= 20) {
            freezeWorld(level);
            findRegions(level);
            int radius = Integer.parseInt(props.getProperty("loadRadius", "4"));
            for (int[] r : regions) {
                for (int x = r[0] - radius; x <= r[0] + radius; x++)
                    for (int z = r[1] - radius; z <= r[1] + radius; z++) {
                        level.setChunkForced(x, z, true);
                        level.getChunk(x, z);
                    }
            }
            stage = 1;
            waitUntil = tick + 2;
        } else if (stage == 1 && tick >= waitUntil) {
            configure();
            ApocalypseChunkData data = ApocalypseChunkData.get(level);
            int savedPhase = Integer.parseInt(props.getProperty("savedPhase", "4"));
            for (int[] r : regions)
                for (int x = r[0] - 4; x <= r[0] + 4; x++)
                    for (int z = r[1] - 4; z <= r[1] + 4; z++)
                        data.setPhase(new ChunkPos(x, z), savedPhase);

            // Optional list of days to process one after the other (step by step)
            String[] days = props.getProperty("days", props.getProperty("day", "50")).split(",");
            for (String d : days) {
                ConfigManager.apocalypseCurrentDay = Integer.parseInt(d.trim());
                // All the chunks of a day as one batch, when the code has batches
                batch("beginBatch");
                for (int[] r : regions)
                    for (int x = r[0] - 2; x <= r[0] + 2; x++)
                        for (int z = r[1] - 2; z <= r[1] + 2; z++)
                            Generator.processChunk(new ChunkToProcess(level, x, z));
                batch("endBatch");
                if ("true".equals(props.getProperty("burnFiresBetweenDays")))
                    burnFires(level);
            }
            dumpRegions(level, "AFTER");
            if ("true".equals(props.getProperty("dropAshOverAshTicks"))) {
                // Old code only: drops the ash ticks that find ash below (they do nothing)
                Block ash = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("judgementday", "ash_block"));
                for (int[] r : regions)
                    for (int x = r[0] - 4; x <= r[0] + 4; x++)
                        for (int z = r[1] - 4; z <= r[1] + 4; z++)
                            ((net.minecraft.world.ticks.LevelChunkTicks<Block>) level.getChunk(x, z).getBlockTicks()).removeIf(
                                    t -> t.type() == ash && t.triggerTick() == level.getGameTime() + 1
                                            && level.getBlockState(t.pos()).is(ash) && level.getBlockState(t.pos().below()).is(ash));
            }
            if ("true".equals(props.getProperty("dumpTicks")))
                dumpTicks(level);
            for (int[] r : regions)
                for (int x = r[0] - 4; x <= r[0] + 4; x++)
                    for (int z = r[1] - 4; z <= r[1] + 4; z++)
                        log("PHASE " + x + " " + z + " " + data.getPhase(new ChunkPos(x, z)));

            String again = props.getProperty("againDay");
            if (again != null) {
                ConfigManager.apocalypseCurrentDay = Integer.parseInt(again);
                for (int[] r : regions)
                    for (int x = r[0] - 2; x <= r[0] + 2; x++)
                        for (int z = r[1] - 2; z <= r[1] + 2; z++)
                            Generator.processChunk(new ChunkToProcess(level, x, z));
                dumpRegions(level, "AGAIN");
            }

            stage = 2;
            waitUntil = tick + Integer.parseInt(props.getProperty("lightTicks", "0"));
        } else if (stage == 2 && tick >= waitUntil) {
            if (Integer.parseInt(props.getProperty("lightTicks", "0")) > 0) {
                lightReport(level);
                if ("true".equals(props.getProperty("dumpTicks")))
                    dumpRegions(level, "LATER");
            }
            finish();
        }
    }

    static void batch(String method) {
        try {
            Generator.class.getMethod(method).invoke(null);
        } catch (NoSuchMethodException e) {
            // older code: no batches
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** No random change to the world between loading and processing (exact comparisons). */
    static void freezeWorld(ServerLevel level) {
        net.minecraft.world.level.GameRules rules = level.getGameRules();
        if ("true".equals(props.getProperty("realistic"))) {
            // Performance runs: the world lives (fire burns out, random ticks), only the
            // clock, the weather and natural spawning stay still
            rules.getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
            rules.getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
            rules.getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
            return;
        }
        rules.getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING).set(0, server);
        rules.getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(false, server);
        rules.getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(false, server);
        rules.getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
    }

    static void configure() {
        ConfigManager.apocalypseMaxDays = Integer.parseInt(props.getProperty("maxDays", "50"));
        ConfigManager.apocalypseEndDay = Integer.parseInt(props.getProperty("endDay", "200"));
        ConfigManager.minDamageHeight = Integer.parseInt(props.getProperty("damageHeight", "60"));
        ConfigManager.minWaterEvaporationHeight = Integer.parseInt(props.getProperty("waterHeight", "60"));
        ConfigManager.apocalypseCurrentDay = Integer.parseInt(props.getProperty("day", "50"));
        // The heights are the ones given: as for an old config, no phase is caught up at load
        ConfigManager.lastAppliedPhase = ConfigManager.UNSET;
        Generator.resetState();
    }

    static void findRegions(ServerLevel level) {
        List<java.util.function.Predicate<Holder<Biome>>> preds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add("deep_ocean");
        preds.add(h -> h.is(BiomeTags.IS_DEEP_OCEAN));
        names.add("river");
        preds.add(h -> h.is(BiomeTags.IS_RIVER) && !h.is(Biomes.FROZEN_RIVER));
        names.add("forest");
        preds.add(h -> h.is(BiomeTags.IS_FOREST));
        names.add("frozen");
        preds.add(h -> h.is(Biomes.FROZEN_OCEAN) || h.is(Biomes.SNOWY_PLAINS) || h.is(Biomes.ICE_SPIKES)
                || h.is(Biomes.FROZEN_RIVER) || h.is(Biomes.SNOWY_TAIGA) || h.is(Biomes.SNOWY_BEACH));
        names.add("beach");
        preds.add(h -> h.is(BiomeTags.IS_BEACH));
        names.add("swamp");
        preds.add(h -> h.is(Biomes.SWAMP) || h.is(Biomes.MANGROVE_SWAMP));
        // Only when asked by name (regions=...): not part of the default set
        names.add("tall_taiga");
        preds.add(h -> h.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA) || h.is(Biomes.OLD_GROWTH_PINE_TAIGA));
        names.add("jungle");
        preds.add(h -> h.is(BiomeTags.IS_JUNGLE));
        names.add("mushroom");
        preds.add(h -> h.is(Biomes.MUSHROOM_FIELDS) || h.is(Biomes.DARK_FOREST));

        String only = props.getProperty("regions");
        BlockPos origin = new BlockPos(0, 64, 0);
        for (int i = 0; i < preds.size(); i++) {
            if (only != null && !("," + only + ",").contains("," + names.get(i) + ","))
                continue;
            if (only == null && i >= 6)
                continue;
            Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(preds.get(i), origin, 6400, 32, 64);
            if (found == null) {
                log("REGION " + names.get(i) + " NOT FOUND");
                continue;
            }
            int cx = found.getFirst().getX() >> 4;
            int cz = found.getFirst().getZ() >> 4;
            boolean overlap = false;
            for (int[] r : regions)
                if (Math.abs(r[0] - cx) <= 10 && Math.abs(r[1] - cz) <= 10)
                    overlap = true;
            if (overlap) {
                log("REGION " + names.get(i) + " OVERLAPS, skipped");
                continue;
            }
            regions.add(new int[] { cx, cz });
            log("REGION " + names.get(i) + " " + cx + " " + cz);
        }
    }

    /** The days between two steps: every fire lit by the mod has burned out. */
    static void burnFires(ServerLevel level) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int[] r : regions)
            for (int x = r[0] - 3; x <= r[0] + 3; x++)
                for (int z = r[1] - 3; z <= r[1] + 3; z++)
                    for (int lx = 0; lx < 16; lx++)
                        for (int lz = 0; lz < 16; lz++)
                            for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                                BlockPos p = new BlockPos((x << 4) + lx, y, (z << 4) + lz);
                                if (level.getBlockState(p).is(Blocks.FIRE))
                                    level.setBlock(p, air, 2 | 16);
                            }
    }

    /** Block ticks scheduled in the regions, in execution order (below = block under the tick). */
    static void dumpTicks(ServerLevel level) {
        List<net.minecraft.world.ticks.ScheduledTick<Block>> all = new ArrayList<>();
        for (int[] r : regions)
            for (int x = r[0] - 4; x <= r[0] + 4; x++)
                for (int z = r[1] - 4; z <= r[1] + 4; z++)
                    ((net.minecraft.world.ticks.LevelChunkTicks<Block>) level.getChunk(x, z).getBlockTicks()).getAll()
                            .forEach(all::add);
        all.sort(net.minecraft.world.ticks.ScheduledTick.DRAIN_ORDER);
        for (net.minecraft.world.ticks.ScheduledTick<Block> t : all)
            log("TICK " + t.pos().toShortString() + " " + net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(t.type())
                    + " " + (t.triggerTick() - level.getGameTime()) + " " + t.priority()
                    + " below=" + net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(t.pos().below()).getBlock()));
        log("TICKS " + all.size());
    }

    static void dumpRegions(ServerLevel level, String tag) {
        final boolean fireAsAir = "true".equals(props.getProperty("hashFireAsAir"));
        final int airId = Block.getId(Blocks.AIR.defaultBlockState());
        long total = 0;
        for (int[] r : regions) {
            for (int x = r[0] - 3; x <= r[0] + 3; x++) {
                for (int z = r[1] - 3; z <= r[1] + 3; z++) {
                    LevelChunk chunk = level.getChunk(x, z);
                    LevelChunkSection[] sections = chunk.getSections();
                    long chunkHash = 1469598103934665603L;
                    for (int s = 0; s < sections.length; s++) {
                        long h = 1469598103934665603L;
                        LevelChunkSection sec = sections[s];
                        for (int ly = 0; ly < 16; ly++)
                            for (int lz = 0; lz < 16; lz++)
                                for (int lx = 0; lx < 16; lx++) {
                                    BlockState bs = sec.getBlockState(lx, ly, lz);
                                    int id = fireAsAir && bs.is(Blocks.FIRE) ? airId : Block.getId(bs);
                                    h = (h ^ id) * 1099511628211L;
                                    h = (h ^ (lx | lz << 4 | ly << 8)) * 1099511628211L;
                                }
                        chunkHash = (chunkHash ^ h) * 1099511628211L;
                        log(tag + " S " + x + " " + z + " " + s + " " + Long.toHexString(h));
                    }
                    List<String> bes = new ArrayList<>();
                    chunk.getBlockEntities().forEach((pos, be) -> bes.add(pos.toShortString() + "="
                            + net.minecraftforge.registries.ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType())));
                    bes.sort(null);
                    log(tag + " C " + x + " " + z + " " + Long.toHexString(chunkHash) + " BE" + bes);
                    long hm = 1469598103934665603L;
                    for (Heightmap.Types type : new Heightmap.Types[] { Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
                            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES })
                        for (long v : chunk.getOrCreateHeightmapUnprimed(type).getRawData())
                            hm = (hm ^ v) * 1099511628211L;
                    log(tag + " HM " + x + " " + z + " " + Long.toHexString(hm));
                    total ^= chunkHash;
                }
            }
            AABB box = new AABB((r[0] - 3) << 4, -64, (r[1] - 3) << 4, (r[0] + 4) << 4, 320, (r[1] + 4) << 4);
            List<String> falling = new ArrayList<>();
            for (FallingBlockEntity e : level.getEntities(EntityType.FALLING_BLOCK, box, e -> true))
                falling.add(e.blockPosition().toShortString() + "=" + Block.getId(e.getBlockState()));
            falling.sort(null);
            log(tag + " FALLING " + r[0] + " " + r[1] + " " + falling);
        }
        log(tag + " TOTAL " + Long.toHexString(total));

        // Optional count of every block state per chunk: dumpCounts=true
        if ("true".equals(props.getProperty("dumpCounts"))) {
            for (int[] r : regions)
                for (int x = r[0] - 3; x <= r[0] + 3; x++)
                    for (int z = r[1] - 3; z <= r[1] + 3; z++) {
                        java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
                        LevelChunk chunk = level.getChunk(x, z);
                        for (LevelChunkSection sec : chunk.getSections())
                            for (int ly = 0; ly < 16; ly++)
                                for (int lz = 0; lz < 16; lz++)
                                    for (int lx = 0; lx < 16; lx++)
                                        counts.merge(sec.getBlockState(lx, ly, lz).toString(), 1, Integer::sum);
                        log(tag + " N " + x + " " + z + " " + counts);
                    }
        }

        // Optional block by block dump: dumpChunks=x:z;x:z  dumpY=min:max
        String dc = props.getProperty("dumpChunks");
        if (dc != null) {
            String[] yr = props.getProperty("dumpY", "40:120").split(":");
            int y0 = Integer.parseInt(yr[0]), y1 = Integer.parseInt(yr[1]);
            for (String c : dc.split(";")) {
                String[] xz = c.split(":");
                int cx = Integer.parseInt(xz[0]), cz = Integer.parseInt(xz[1]);
                for (int y = y0; y <= y1; y++)
                    for (int lx = 0; lx < 16; lx++)
                        for (int lz = 0; lz < 16; lz++) {
                            BlockPos p = new BlockPos((cx << 4) + lx, y, (cz << 4) + lz);
                            BlockState st = level.getBlockState(p);
                            if (!st.isAir())
                                log(tag + " B " + p.toShortString() + " " + st);
                        }
            }
        }
    }

    static void lightReport(ServerLevel level) {
        int fires = 0, firesLit = 0, columns = 0, skyBad = 0, logs = 0, leaves = 0, mushroom = 0;
        for (int[] r : regions) {
            for (int x = r[0] - 2; x <= r[0] + 2; x++) {
                for (int z = r[1] - 2; z <= r[1] + 2; z++) {
                    LevelChunk chunk = level.getChunk(x, z);
                    for (int lx = 0; lx < 16; lx++)
                        for (int lz = 0; lz < 16; lz++) {
                            int wx = (x << 4) + lx, wz = (z << 4) + lz;
                            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) + 1;
                            if (top < level.getMaxBuildHeight()) {
                                columns++;
                                int sky = level.getBrightness(LightLayer.SKY, new BlockPos(wx, top, wz));
                                if (sky < 15) {
                                    skyBad++;
                                    badPerChunk.merge(x + ":" + z, 1, Integer::sum);
                                    if (skyBad <= 8) {
                                        // Where the light stops: the chunk's sky sources and the
                                        // light a few blocks up
                                        StringBuilder up = new StringBuilder();
                                        for (int dy = 0; dy <= 48; dy += 8)
                                            up.append(' ').append(top + dy).append('=')
                                                    .append(level.getBrightness(LightLayer.SKY, new BlockPos(wx, top + dy, wz)));
                                        log("SKYBAD " + wx + " " + top + " " + wz + " sky=" + sky + " lowestSource="
                                                + chunk.getSkyLightSources().getLowestSourceY(lx, lz) + " top="
                                                + chunk.getBlockState(new BlockPos(wx, top - 1, wz)) + " up:" + up);
                                        if (skyBad <= 3) {
                                            // What the column and its sides hold above the top
                                            StringBuilder col = new StringBuilder();
                                            for (int y = top - 2; y < top + 30; y++) {
                                                BlockState c = chunk.getBlockState(new BlockPos(wx, y, wz));
                                                if (!c.isAir())
                                                    col.append(' ').append(y).append('=').append(c.getBlock().getDescriptionId()
                                                            .replace("block.minecraft.", "").replace("block.judgementday.", ""));
                                            }
                                            log("SKYCOL " + wx + " " + wz + col);
                                        }
                                    }
                                }
                            }
                            for (int y = top - 1; y > top - 40; y--) {
                                BlockPos p = new BlockPos(wx, y, wz);
                                BlockState st = chunk.getBlockState(p);
                                if (st.is(Blocks.RED_MUSHROOM_BLOCK) || st.is(Blocks.BROWN_MUSHROOM_BLOCK)
                                        || st.is(Blocks.MUSHROOM_STEM))
                                    mushroom++;
                                if (st.is(net.minecraft.tags.BlockTags.LOGS))
                                    logs++;
                                else if (st.is(net.minecraft.tags.BlockTags.LEAVES))
                                    leaves++;
                                if (st.is(Blocks.FIRE)) {
                                    fires++;
                                    if (level.getBrightness(LightLayer.BLOCK, p) >= 15)
                                        firesLit++;
                                }
                            }
                        }
                }
            }
        }
        log("LIGHT columns=" + columns + " skyBelow15=" + skyBad + " fires=" + fires + " firesLit=" + firesLit
                + " logs=" + logs + " leaves=" + leaves + " mushroom=" + mushroom + " badChunks=" + badPerChunk);
    }

    static final java.util.Map<String, Integer> badPerChunk = new java.util.TreeMap<>();

    /** Living mobs around the player, by type; "exposed" ones see the sky out of water. */
    static void mobReport(ServerLevel level) {
        java.util.Map<String, int[]> counts = new java.util.TreeMap<>();
        BlockPos c = fakePlayer.blockPosition();
        AABB box = new AABB(c.getX() - 48, level.getMinBuildHeight(), c.getZ() - 48, c.getX() + 48,
                level.getMaxBuildHeight(), c.getZ() + 48);
        for (net.minecraft.world.entity.Mob mob : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box)) {
            if (!mob.isAlive())
                continue;
            int[] n = counts.computeIfAbsent(EntityType.getKey(mob.getType()).getPath(), k -> new int[2]);
            n[0]++;
            if (!mob.isInWater() && level.canSeeSky(mob.blockPosition()))
                n[1]++;
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v[0]).append("/exposed").append(v[1]));
        log("MOBS" + sb);
    }

    // ---------------------------------------------------------------- utils

    static void log(String s) {
        out.add(s);
        JudgementDayMod.LOGGER.info("[SELFTEST] " + (s.length() > 200 ? s.substring(0, 200) + "..." : s));
    }

    static void finish() {
        log("FALLING joined=" + fallingJoined + " types=" + fallingTypes);
        for (String w : fallingWhere)
            log("FALLINGAT " + w);
        try {
            Files.write(FMLPaths.GAMEDIR.get().resolve(props.getProperty("output", "selftest-out.txt")), out);
        } catch (IOException e) {
            e.printStackTrace();
        }
        log("DONE");
        server.halt(false);
        server = null;
    }
}
