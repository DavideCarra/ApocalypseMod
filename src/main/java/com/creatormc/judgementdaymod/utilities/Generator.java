package com.creatormc.judgementdaymod.utilities;

import java.util.ArrayList;
import java.util.List;
import com.creatormc.judgementdaymod.eventHandlers.DayTracker;
import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.ModBlocks;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import io.netty.util.internal.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

public class Generator {
    // Calculates days per phase (divides maxDays into 5 phases of 20% each)
    private static int daysPerPhase = ConfigManager.apocalypseMaxDays / 5;
    private static int previousDay = 0 - daysPerPhase; // day 0 minus one phase to give the player time to start

    public static void handleDayEvent(int day, ServerPlayer player) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;

        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null)
            return;

        // Subtract one phase to start at day 0 and give the player preparation time
        int phaseDay = day - daysPerPhase;

        // Calculate which phase the current day belongs to (before the start)
        int currentPhaseNumber = Math.floorDiv(ConfigManager.apocalypseCurrentDay, daysPerPhase); // -1, 0, 1, 2, 3, 4, 5
        int previousPhaseNumber = Math.floorDiv(previousDay, daysPerPhase); // -1, 0, 1, 2, 3, 4, 5

        System.out.println("Current Phase: " + currentPhaseNumber + ", Previous Phase: " + previousPhaseNumber);

        // Check if we have entered a new phase
        if (currentPhaseNumber > previousPhaseNumber && phaseDay <= ConfigManager.apocalypseMaxDays) {

            previousDay = currentPhaseNumber;

            // Current drying and damage heights
            int baseDamageHeight = ConfigManager.minDamageHeight;
            int baseWaterHeight = ConfigManager.minWaterEvaporationHeight;

            // Calculate new heights based on phase
            int newDamageHeight = baseDamageHeight - (currentPhaseNumber * (baseDamageHeight / 5));
            int newWaterHeight = baseWaterHeight - (currentPhaseNumber * (baseWaterHeight / 5));

            // Ensure they do not go below zero
            ConfigManager.minDamageHeight = Math.max(newDamageHeight, 0);
            ConfigManager.minWaterEvaporationHeight = Math.max(newWaterHeight, 0);

            ConfigManager.save();

            resetPlayerChunks(player);

            // Calculate percentage for Phase
            int percent = Phase.toPercent(phaseDay, ConfigManager.apocalypseMaxDays);
            Phase currentPhase = Phase.getPhaseForPercent(percent);

            // Send title and subtitle
            player.connection.send(new ClientboundSetTitleTextPacket(currentPhase.getTitleComponent()));
            player.connection.send(new ClientboundSetSubtitleTextPacket(currentPhase.getDescriptionComponent()));
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        }

        // Upon reaching the maximum, activate the final apocalypse
        if (phaseDay >= ConfigManager.apocalypseMaxDays && !ApocalypseManager.isApocalypseActive()) {
            ConfigManager.apocalypseCurrentDay = ConfigManager.apocalypseMaxDays;
            ApocalypseManager.startApocalypse();

            // === DISABLE ALL RAIN ===
            if (world.isRaining() || world.isThundering()) {
                world.setWeatherParameters(0, 0, false, false); // force clear sky immediately
            }

            // Prevent future reactivation: infinite sunlight
            world.setWeatherParameters(Integer.MAX_VALUE, 0, false, false);
            System.out.println("[Apocalypse] Rain and thunderstorms permanently disabled.");

            ConfigManager.save();
        }
    }

    @SuppressWarnings("resource")
    public static void processChunk(ChunkToProcess chunkInfo) {
        try {
            final ServerLevel level = chunkInfo.getLevel();
            final int chunkX = chunkInfo.getChunkX();
            final int chunkZ = chunkInfo.getChunkZ();

            // Early returns
            if (!level.getServer().isSameThread() || !level.hasChunk(chunkX, chunkZ)) {
                return;
            }

            final LevelChunk lc = level.getChunk(chunkX, chunkZ);
            final LevelChunkSection[] sections = lc.getSections();
            final int startX = chunkX << 4;
            final int startZ = chunkZ << 4;
            final int minSection = lc.getMinSection();
            final int minBuildHeight = ConfigManager.minWaterEvaporationHeight;
            final int maxBuildHeight = level.getMaxBuildHeight();
            final double percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

            // Cache of most used block states
            final BlockState ashState = ModBlocks.ASH_BLOCK.get().defaultBlockState();
            final BlockState fireState = Blocks.FIRE.defaultBlockState();
            final BlockState airState = Blocks.AIR.defaultBlockState();

            // Optimization: ThreadLocalRandom faster for single-thread
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            final float apocalypseThreshold = ConfigManager.apocalypseCurrentDay * 0.01f;

            // Optimization: preallocate realistic capacity (max 256 blocks per chunk)
            final List<BlockPos> changedPositions = new ArrayList<>(256);
            final boolean[] dirtySection = new boolean[sections.length];

            // Track touched columns
            final boolean[][] touchedColumn = new boolean[16][16];

            // Cache heightmap - faster direct access
            final Heightmap wsHM = lc.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);

            // Optimization: precompute section indexes to avoid repeated division
            final int sectionsCount = sections.length;

            // First remove water, iterate from -1 to solve overlapping issues
            // (merged with surface transformations in same double loop lx/lz)
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    final int worldX = startX + lx;
                    final int worldZ = startZ + lz;

                    // --- EVAPORATION ---
                    if (percent >= 40.0) {
                        for (int removeY = maxBuildHeight - 1; removeY >= minBuildHeight; removeY--) {
                            int targetSecIndex = (removeY >> 4) - minSection;
                            if (targetSecIndex < 0 || targetSecIndex >= sections.length)
                                continue;

                            LevelChunkSection targetSection = sections[targetSecIndex];
                            if (targetSection == null)
                                continue;

                            int targetLy = removeY & 15;
                            BlockState targetState = targetSection.getBlockState(lx, targetLy, lz);

                            if (Analyzer.isEvaporable(targetState)) {
                                // Remove the current block
                                targetSection.setBlockState(lx, targetLy, lz, airState, false);
                                dirtySection[targetSecIndex] = true;
                                changedPositions.add(new BlockPos(worldX, removeY, worldZ));

                                // Update heightmap to reflect air block
                                wsHM.update(lx, removeY, lz, airState);

                                // --- WALL FIX: if on chunk border, clear adjacent block in neighbor section ---
                                // todo: not ideal, may refine later
                                if (lx == 0 || lx == 15 || lz == 0 || lz == 15) {
                                    // Near X
                                    if (lx == 0 || lx == 15) {
                                        int nx = worldX + (lx == 0 ? -1 : 1);
                                        int neighborChunkX = nx >> 4;
                                        int neighborChunkZ = worldZ >> 4;

                                        if (level.hasChunk(neighborChunkX, neighborChunkZ)) {
                                            LevelChunk neighborChunk = level.getChunk(neighborChunkX, neighborChunkZ);
                                            int neighborSecIndex = (removeY >> 4) - neighborChunk.getMinSection();
                                            if (neighborSecIndex >= 0
                                                    && neighborSecIndex < neighborChunk.getSections().length) {
                                                LevelChunkSection neighborSection = neighborChunk
                                                        .getSections()[neighborSecIndex];
                                                if (neighborSection != null) {
                                                    int nxLocal = nx & 15;
                                                    BlockState neighborState = neighborSection.getBlockState(nxLocal,
                                                            removeY & 15, lz);
                                                    if (Analyzer.isEvaporable(neighborState)) {
                                                        neighborSection.setBlockState(nxLocal, removeY & 15, lz,
                                                                airState, false);
                                                        neighborSection.recalcBlockCounts();
                                                        neighborChunk.setUnsaved(true);
                                                        changedPositions.add(new BlockPos(nx, removeY, worldZ));
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Near Z
                                    if (lz == 0 || lz == 15) {
                                        int nz = worldZ + (lz == 0 ? -1 : 1);
                                        int neighborChunkX = worldX >> 4;
                                        int neighborChunkZ = nz >> 4;

                                        if (level.hasChunk(neighborChunkX, neighborChunkZ)) {
                                            LevelChunk neighborChunk = level.getChunk(neighborChunkX, neighborChunkZ);
                                            int neighborSecIndex = (removeY >> 4) - neighborChunk.getMinSection();
                                            if (neighborSecIndex >= 0
                                                    && neighborSecIndex < neighborChunk.getSections().length) {
                                                LevelChunkSection neighborSection = neighborChunk
                                                        .getSections()[neighborSecIndex];
                                                if (neighborSection != null) {
                                                    int nzLocal = nz & 15;
                                                    BlockState neighborState = neighborSection.getBlockState(lx,
                                                            removeY & 15, nzLocal);
                                                    if (Analyzer.isEvaporable(neighborState)) {
                                                        neighborSection.setBlockState(lx, removeY & 15, nzLocal,
                                                                airState, false);
                                                        neighborSection.recalcBlockCounts();
                                                        neighborChunk.setUnsaved(true);
                                                        changedPositions.add(new BlockPos(worldX, removeY, nz));
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        }

                    }

                    // --- SURFACE TRANSFORMATION ---
                    if (random.nextFloat() <= apocalypseThreshold) {
                        int topY = wsHM.getFirstAvailable(lx, lz) - 1;
                        if (topY < minBuildHeight)
                            continue;

                        int secIndex = (topY >> 4) - minSection;
                        if (secIndex < 0 || secIndex >= sectionsCount)
                            continue;

                        LevelChunkSection section = sections[secIndex];

                        int ly = topY & 15;
                        BlockState currentBlock = section.getBlockState(lx, ly, lz);

                        BlockState newState = ashState;
                        int targetY = topY;

                        if (Analyzer.isSkippable(currentBlock)) {
                            // Find first non-ash block descending
                            for (int y = topY - 1; y > minBuildHeight; y--) {
                                final int checkSecIndex = (y >> 4) - minSection;
                                if (checkSecIndex < 0 || checkSecIndex >= sectionsCount)
                                    break;

                                final LevelChunkSection checkSection = sections[checkSecIndex];
                                if (checkSection == null || checkSection.hasOnlyAir())
                                    continue;

                                final int checkLy = y & 15;
                                BlockState foundBlock = checkSection.getBlockState(lx, checkLy, lz);
                                if (foundBlock.isAir())
                                    continue;

                                if (!Analyzer.isSkippable(foundBlock)) {
                                    targetY = y;
                                    // Determine transformation type inline
                                    if (Analyzer.canBurn(foundBlock, null, null)) {
                                        newState = fireState;
                                    } else if (Analyzer.isEvaporable(foundBlock)) {
                                        newState = airState;
                                    } else {
                                        newState = ashState;
                                    }
                                    break;
                                }
                            }
                            if (targetY == topY)
                                continue; // No block found
                        } else {
                            if (Analyzer.canBurn(currentBlock, null, null)) {
                                newState = fireState;
                            } else if (Analyzer.isEvaporable(currentBlock)) {
                                newState = airState;
                            } else {
                                newState = ashState;
                            }
                        }

                        int finalSecIndex = (targetY >> 4) - minSection;
                        if (finalSecIndex >= 0 && finalSecIndex < sectionsCount) {
                            LevelChunkSection targetSection = sections[finalSecIndex];
                            if (targetSection != null) {
                                int finalLy = targetY & 15;
                                targetSection.setBlockState(lx, finalLy, lz, newState, false);
                                dirtySection[finalSecIndex] = true;
                                changedPositions.add(new BlockPos(startX + lx, targetY, startZ + lz));
                            }
                        }
                    }
                }
            }

            // Early return if no changes
            if (changedPositions.isEmpty())
                return;

            // Batch update modified sections
            for (int i = 0; i < sectionsCount; i++) {
                if (dirtySection[i] && sections[i] != null) {
                    sections[i].recalcBlockCounts();
                }
            }

            lc.setUnsaved(true);
            Heightmap.primeHeightmaps(lc, lc.getStatus().heightmapsAfter());

            final Block ashBlockRef = ModBlocks.ASH_BLOCK.get();
            final Block fireBlockRef = Blocks.FIRE;
            final int tickDelay = 40;

            // Schedule ticks, falling blocks, and light corrections
            for (BlockPos pos : changedPositions) {
                BlockState state = level.getBlockState(pos);

                if (state.getBlock() == ashBlockRef) {
                    level.scheduleTick(pos, ashBlockRef, tickDelay);
                }

                if (state.getBlock() == fireBlockRef) {
                    level.scheduleTick(pos, fireBlockRef, tickDelay);
                }

                if (state.getBlock() instanceof FallingBlock) {
                    BlockPos belowPos = pos.below();
                    if (FallingBlock.isFree(level.getBlockState(belowPos))) {
                        FallingBlockEntity.fall(level, pos, state);
                    }
                }

                int lx = pos.getX() & 15;
                int lz = pos.getZ() & 15;
                touchedColumn[lx][lz] = true; // mark for lighting update
            }

            // Refresh entire chunk for client updates
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(lc,
                    level.getLightEngine(), null, null);

            // Packet to "forget" the chunk before resending
            ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket(chunkX, chunkZ);

            for (ServerPlayer viewer : level.players()) {
                // Send only to players currently viewing this chunk
                if (level.getChunkSource().chunkMap.getPlayers(lc.getPos(), false).contains(viewer)) {
                    // Force client to forget chunk
                    viewer.connection.send(forget);

                    // Wait 1 tick before resending to prevent client ignoring resend
                    level.getServer().execute(() -> {
                        viewer.connection.send(packet);
                    });
                }
            }

            // After bulk edit, before packet resend
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (!touchedColumn[lx][lz])
                        continue;
                    int topY = wsHM.getFirstAvailable(lx, lz); // above current top
                    BlockPos pulse = new BlockPos(startX + lx, topY, startZ + lz);

                    // Place temporary light
                    level.setBlock(pulse, Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
                    // Remove next tick to avoid leftovers
                    level.scheduleTick(pulse, Blocks.LIGHT, 1);
                }
            }

        } catch (Exception e) {
            System.err.println("Error processChunk: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Makes all chunks "unwatched"
    // Used to reload all chunks when a new apocalypse phase starts
    public static void resetPlayerChunks(ServerPlayer player) {
        if (player == null || player.hasDisconnected())
            return;

        ServerLevel level = player.serverLevel();
        ChunkPos playerPos = new ChunkPos(player.blockPosition());
        int viewDistance = level.getServer().getPlayerList().getViewDistance() + 1;

        // Queue all visible chunks to the same queue emptied in onServerTick
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int cx = playerPos.x + dx;
                int cz = playerPos.z + dz;
                LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk != null) {
                    DayTracker.enqueueChunk(new ChunkToProcess(level, cx, cz));
                }
            }
        }
    }

}
