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
            if (scenario().startsWith("compare")) {
                compareTick();
            } else if (scenario().equals("generate")) {
                generateTick();
            } else if (scenario().equals("clientlight")) {
                clientLightTick();
            }
        } catch (Throwable t) {
            log("EXCEPTION " + t);
            for (StackTraceElement el : t.getStackTrace())
                log("   at " + el);
            finish();
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

    static void clientLightTick() {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (stage == 0 && tick >= 20) {
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
                    Generator.resetPlayersChunks(level);
                    log("PHASE CHANGE day=" + change);
                    stage = 2;
                    waitUntil = tick + Integer.parseInt(props.getProperty("lightTicks", "600"));
                } else {
                    compareClientLight(level);
                    finish();
                }
            }
        } else if (stage == 2) {
            drainPackets();
            if (tick >= waitUntil) {
                compareClientLight(level);
                lightReport(level);
                finish();
            }
        }
    }

    static void drainPackets() {
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            handlePacket(msg);
        }
    }

    @SuppressWarnings("unchecked")
    static void handlePacket(Object msg) {
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
                            if (!explained)
                                unexplained++;
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
                for (int[] r : regions)
                    for (int x = r[0] - 2; x <= r[0] + 2; x++)
                        for (int z = r[1] - 2; z <= r[1] + 2; z++)
                            Generator.processChunk(new ChunkToProcess(level, x, z));
                if ("true".equals(props.getProperty("burnFiresBetweenDays")))
                    burnFires(level);
            }
            dumpRegions(level, "AFTER");
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
            }
            finish();
        }
    }

    /** No random change to the world between loading and processing (exact comparisons). */
    static void freezeWorld(ServerLevel level) {
        net.minecraft.world.level.GameRules rules = level.getGameRules();
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

        String only = props.getProperty("regions");
        BlockPos origin = new BlockPos(0, 64, 0);
        for (int i = 0; i < preds.size(); i++) {
            if (only != null && !("," + only + ",").contains("," + names.get(i) + ","))
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
        int fires = 0, firesLit = 0, columns = 0, skyBad = 0;
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
                                if (level.getBrightness(LightLayer.SKY, new BlockPos(wx, top, wz)) < 15)
                                    skyBad++;
                            }
                            for (int y = top - 1; y > top - 40; y--) {
                                BlockPos p = new BlockPos(wx, y, wz);
                                BlockState st = chunk.getBlockState(p);
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
        log("LIGHT columns=" + columns + " skyBelow15=" + skyBad + " fires=" + fires + " firesLit=" + firesLit);
    }

    // ---------------------------------------------------------------- utils

    static void log(String s) {
        out.add(s);
        JudgementDayMod.LOGGER.info("[SELFTEST] " + (s.length() > 200 ? s.substring(0, 200) + "..." : s));
    }

    static void finish() {
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
