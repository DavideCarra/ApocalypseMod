package com.creatormc.judgementdaymod.utilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.creatormc.judgementdaymod.handlers.DayTracker;
import com.creatormc.judgementdaymod.handlers.PhaseTitleOverlay;
import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.setup.ModBlocks;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import io.netty.util.internal.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.Fluids;

public class Generator {
    // Calculates days per phase (divides maxDays into 5 phases of 20% each)
    private static int daysPerPhase = ConfigManager.apocalypseMaxDays / 5;
    private static int previousDay = 0 - daysPerPhase; // day 0 minus one phase to give player time to start
    private static int decelerateDaysPerPhase = daysPerPhase * 2; // decelerate phase frequency: double duration
    private static ApocalypsePhases.Phase lastPlayedPhase = null;

    public static void handleDayEvent(ServerPlayer player) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;

        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null)
            return;

        // Calculate which phase the current day belongs to
        int currentPhaseNumber = Math.floorDiv(ConfigManager.apocalypseCurrentDay, daysPerPhase);
        int previousPhaseNumber = Math.floorDiv(previousDay, daysPerPhase);

        // Check if we have entered a new phase
        if (currentPhaseNumber > previousPhaseNumber
                && ConfigManager.apocalypseCurrentDay <= ConfigManager.apocalypseMaxDays) {

            previousDay = ConfigManager.apocalypseCurrentDay;

            // Current damage and water heights
            int baseDamageHeight = ConfigManager.minDamageHeight;
            int baseWaterHeight = ConfigManager.minWaterEvaporationHeight;

            // Calculate new heights based on phase
            int newDamageHeight = baseDamageHeight - (currentPhaseNumber * (baseDamageHeight / 5));
            int newWaterHeight = baseWaterHeight - (currentPhaseNumber * (baseWaterHeight / 5));

            // Ensure they do not go below minimum
            ConfigManager.minDamageHeight = Math.max(newDamageHeight, -20);
            ConfigManager.minWaterEvaporationHeight = Math.max(newWaterHeight, -20);

            ConfigManager.save();
            resetPlayerChunks(player);

            // Calculate percentage for Phase
            int percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);
            Phase currentPhase = Phase.getPhaseForPercent(percent);
            PhaseTitleOverlay.displayPhaseTitle(currentPhase.getTitleComponent(),
                    currentPhase.getDescriptionComponent());

            // Start music for the new phase
            if (ConfigManager.apocalypseCurrentDay <= ConfigManager.apocalypseEndDay) {
                playPhaseMusic(player, currentPhase);
            }
        }

        // Upon reaching the maximum, activate the final apocalypse
        if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseMaxDays
                && !ApocalypseManager.isApocalypseActive()
                && ConfigManager.apocalypseCurrentDay < ConfigManager.apocalypseEndDay) {
            ApocalypseManager.startApocalypse();

            // Disable all rain permanently
            if (world.isRaining() || world.isThundering()) {
                world.setWeatherParameters(0, 0, false, false);
            }
            world.setWeatherParameters(Integer.MAX_VALUE, 0, false, false);
            JudgementDayMod.LOGGER.info("[Apocalypse] Rain and thunderstorms permanently disabled.");

        } else if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseEndDay) {
            world.setWeatherParameters(12000, 12000, false, false);
            JudgementDayMod.LOGGER.info("[Apocalypse] Final stage reached — weather cycle restored.");
        }

        // Post-apocalypse extension phase
        if (ApocalypseManager.isApocalypseActive()) {
            if (ConfigManager.apocalypseCurrentDay < ConfigManager.apocalypseEndDay) {
                if (ConfigManager.apocalypseCurrentDay % decelerateDaysPerPhase == 0) {
                    resetPlayerChunks(player);
                }
            } else if (ConfigManager.apocalypseCurrentDay == ConfigManager.apocalypseEndDay) {
                PhaseTitleOverlay.displayPhaseTitle(Phase.PHASE_END.getTitleComponent(),
                        Phase.PHASE_END.getDescriptionComponent());
            }
        }
    }

    @SuppressWarnings("resource")
    public static void processChunk(ChunkToProcess chunkInfo) {
        try {
            final ServerLevel level = chunkInfo.getLevel();
            final int chunkX = chunkInfo.getChunkX();
            final int chunkZ = chunkInfo.getChunkZ();

            if (!level.getServer().isSameThread() || !level.hasChunk(chunkX, chunkZ)) {
                return;
            }

            // Process only overworld
            if (level.dimension() != Level.OVERWORLD) {
                return;
            }

            final LevelChunk chunk = level.getChunk(chunkX, chunkZ);

            // Calculate current phase number (0-4)
            final float percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

            int currentPhaseNumber = Math.min(5, (int) (percent / 20.0f));

            ApocalypseChunkData data = ApocalypseChunkData.get(level);
            ChunkPos chunkPos = chunk.getPos();
            int savedPhase = data.getPhase(chunkPos);

            // If apocalypse has ended, skip ALL processing
            if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseEndDay && savedPhase == 999) {
                // Mark as permanently completed (use a special value like 999)
                return;
            }

            // Skip if already processed for this phase or later
            if (currentPhaseNumber <= savedPhase) {
                return;
            }


            boolean hadChanges = false;

            // PHASE 1: Water evaporation
            if (percent >= 40.0 || ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseMaxDays) {
                hadChanges |= evaporateWater(chunk, level, ConfigManager.minWaterEvaporationHeight,
                        level.getMaxBuildHeight());
            }

            // PHASE 2: Surface transformation
            hadChanges |= transformSurface(chunk, level, percent);

            // Set phase to 999, to stop processing
            if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseEndDay) {
                data.setPhase(chunkPos, 999);
            } else {
                // Update phase (0-4 are valid phases, 5+ means "max apocalypse reached")
                data.setPhase(chunkPos, Math.min(currentPhaseNumber, 5));
            }

            // PHASE 3: Update clients
            if (hadChanges) {
                updateClients(chunk, level);
            }

        } catch (Exception e) {
            System.err.println("[Apocalypse] Error processing chunk: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * WATER EVAPORATION
     * Removes water blocks efficiently using direct section access.
     * Water above solid ground becomes fire, water above water becomes air.
     */
    private static boolean evaporateWater(LevelChunk chunk, ServerLevel level, int minBuildHeight, int maxBuildHeight) {
        final BlockState airState = Blocks.AIR.defaultBlockState();

        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        boolean hadChanges = false;

        // Predicate for water detection
        final Predicate<BlockState> isEvaporable = state -> Analyzer.isEvaporable(state);

        // Process 3x3 chunk grid (current chunk + 8 neighbors)
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                final int targetChunkX = chunkX + offsetX;
                final int targetChunkZ = chunkZ + offsetZ;

                if (!level.hasChunk(targetChunkX, targetChunkZ))
                    continue;

                final LevelChunk targetChunk = level.getChunk(targetChunkX, targetChunkZ);
                final LevelChunkSection[] sections = targetChunk.getSections();
                final int minSection = targetChunk.getMinSection();
                final int startX = targetChunkX << 4;
                final int startZ = targetChunkZ << 4;

                // Calculate Y-section range to skip empty vertical space
                final int minSecIndex = Math.max(0, (minBuildHeight >> 4) - minSection);
                final int maxSecIndex = Math.min(sections.length - 1, (maxBuildHeight >> 4) - minSection);

                List<Integer> sectionsWithWater = new ArrayList<>();
                for (int secIndex = maxSecIndex; secIndex >= minSecIndex; secIndex--) {
                    final LevelChunkSection section = sections[secIndex];

                    if (section == null || section.hasOnlyAir())
                        continue;

                    // Fast check: does this section contain water?
                    if (section.maybeHas(isEvaporable)) {
                        sectionsWithWater.add(secIndex);
                    }
                }

                // If no section has water, skip this chunk entirely
                if (sectionsWithWater.isEmpty())
                    continue;

                // Iterate ONLY over sections that contain water
                for (int secIndex : sectionsWithWater) {
                    final LevelChunkSection section = sections[secIndex];

                    if (section == null || section.hasOnlyAir())
                        continue;

                    final int sectionWorldY = (secIndex + minSection) << 4;
                    boolean sectionModified = false;

                    // Process blocks within this section (Y: 15 → 0)
                    for (int ly = 15; ly >= 0; ly--) {
                        final int worldY = sectionWorldY + ly;

                        // Stop if we've reached the evaporation floor
                        if (worldY <= ConfigManager.minWaterEvaporationHeight)
                            break;

                        if (worldY < minBuildHeight || worldY >= maxBuildHeight)
                            continue;

                        // Check all 16x16 horizontal positions in this Y-layer
                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                final BlockState state = section.getBlockState(lx, ly, lz);

                                if (!Analyzer.isEvaporable(state))
                                    continue;

                                // Validity controls
                                if (level.isOutsideBuildHeight(mutablePos))
                                    continue;
                                if (!level.isClientSide && level.isDebug())
                                    continue;

                                mutablePos.set(startX + lx, worldY, startZ + lz);

                                // Local coordinates (0–15) within the section
                                int localX = mutablePos.getX() & 15;
                                int localY = worldY & 15;
                                int localZ = mutablePos.getZ() & 15;

                                // Previous block state
                                BlockState oldState = section.setBlockState(localX, localY, localZ, airState);

                                if (Analyzer.isEvaporable(oldState)) {
                                    BlockPos above = mutablePos.above();
                                    BlockState aboveState = level.getBlockState(above);
                                    if (aboveState.getBlock() instanceof FallingBlock) {
                                        level.scheduleTick(above, aboveState.getBlock(), 1);
                                    }
                                }

                                if (oldState == airState) {
                                    continue; // No actual change
                                }

                                // Light management
                                if (LightEngine.hasDifferentLightProperties(targetChunk, mutablePos, oldState,
                                        airState)) {
                                    ProfilerFiller profiler = level.getProfiler();
                                    profiler.push("updateSkyLightSources");
                                    targetChunk.getSkyLightSources().update(targetChunk, localX, worldY, localZ);
                                    profiler.popPush("queueCheckLight");
                                    level.getChunkSource().getLightEngine().checkBlock(mutablePos);
                                    profiler.pop();
                                }

                            }
                        }
                        sectionModified = true;
                    }

                    // Recalculate block counts immediately after modifying this section
                    if (sectionModified) {
                        section.recalcBlockCounts();
                        hadChanges = true;
                    }
                }

                // Mark chunk for saving if modified
                if (hadChanges) {
                    targetChunk.setUnsaved(true);
                    Heightmap.primeHeightmaps(targetChunk, targetChunk.getStatus().heightmapsAfter());
                }
            }
        }

        return hadChanges;
    }

    /**
     * SURFACE TRANSFORMATION
     * Converts surface blocks to ash, fire, or air based on apocalypse progress
     */
    private static boolean transformSurface(LevelChunk chunk, ServerLevel level, float percent) {
        final LevelChunkSection[] sections = chunk.getSections();
        final int startX = chunk.getPos().x << 4;
        final int startZ = chunk.getPos().z << 4;
        final int minSection = chunk.getMinSection();
        final int minBuildHeight = ConfigManager.minWaterEvaporationHeight;
        final int sectionsCount = sections.length;
        Set<BlockPos> waterNeighborsToUpdate = new HashSet<>();

        // Cache block states to avoid repeated calls
        final BlockState ashState = ModBlocks.ASH_BLOCK.get().defaultBlockState();
        final Block ashBlock = ModBlocks.ASH_BLOCK.get();
        final BlockState fireState = Blocks.FIRE.defaultBlockState();
        final Block fireBlock = Blocks.FIRE;
        final BlockState airState = Blocks.AIR.defaultBlockState();

        // ThreadLocalRandom is faster for single-threaded operations
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final float apocalypseThreshold = percent / 100f;

        // Track which sections were modified
        final boolean[] dirtySection = new boolean[sectionsCount];
        boolean hadChanges = false;

        // Cache heightmap for fast surface access
        final Heightmap wsHM = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);

        // Single pass over all columns
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // Random transformation based on apocalypse progress
                if (random.nextFloat() > apocalypseThreshold)
                    continue;

                int topY = wsHM.getFirstAvailable(lx, lz) - 1;
                if (topY < minBuildHeight)
                    continue;

                int secIndex = (topY >> 4) - minSection;
                if (secIndex < 0 || secIndex >= sectionsCount)
                    continue;

                LevelChunkSection section = sections[secIndex];
                if (section == null)
                    continue;

                int ly = topY & 15;
                BlockState currentBlock = section.getBlockState(lx, ly, lz);

                BlockState newState = ashState;
                Block newBlock = ashBlock;
                int targetY = topY;

                // If current block is skippable (ash/air), find first solid block below
                if (Analyzer.isSkippable(currentBlock)) {
                    boolean foundSolid = false;

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
                            foundSolid = true;

                            // Determine transformation type
                            if (Analyzer.canBurn(foundBlock, null, null)) {
                                newState = fireState;
                                newBlock = fireBlock;
                            } else if (Analyzer.isEvaporable(foundBlock)) {
                                newState = airState;
                                newBlock = null;
                            } else {
                                newState = ashState;
                                newBlock = ashBlock;
                            }
                            break;
                        }
                    }

                    if (!foundSolid)
                        continue;

                } else {
                    // Current block is solid, transform it directly
                    if (Analyzer.canBurn(currentBlock, null, null)) {
                        newState = fireState;
                        newBlock = fireBlock;
                    } else if (Analyzer.isEvaporable(currentBlock)) {
                        newState = airState;
                        newBlock = null;
                    } else {
                        newState = ashState;
                        newBlock = ashBlock;
                    }
                }

                // Apply transformation
                int finalSecIndex = (targetY >> 4) - minSection;
                if (finalSecIndex >= 0 && finalSecIndex < sectionsCount) {
                    LevelChunkSection targetSection = sections[finalSecIndex];
                    if (targetSection != null) {
                        int finalLy = targetY & 15;

                        // Save old state BEFORE changing it
                        BlockState oldState = targetSection.getBlockState(lx, finalLy, lz);

                        targetSection.setBlockState(lx, finalLy, lz, newState, false);
                        dirtySection[finalSecIndex] = true;
                        hadChanges = true;

                        BlockPos pos = new BlockPos(startX + lx, targetY, startZ + lz);

                        // Schedule ticks inline
                        if (newBlock == ashBlock) {
                            level.scheduleTick(pos, ashBlock, 1);
                        } else if (newBlock == fireBlock) {
                            level.scheduleTick(pos, fireBlock, 1);
                        } else if (newBlock == null && Analyzer.isEvaporable(oldState)) {
                            // Add neighbors to set instead of immediate check
                            waterNeighborsToUpdate.add(pos.north());
                            waterNeighborsToUpdate.add(pos.south());
                            waterNeighborsToUpdate.add(pos.east());
                            waterNeighborsToUpdate.add(pos.west());
                            waterNeighborsToUpdate.add(pos.above());
                            waterNeighborsToUpdate.add(pos.below());
                        }
                        // Handle falling blocks
                        if (newState.getBlock() instanceof FallingBlock) {
                            BlockPos belowPos = pos.below();
                            if (FallingBlock.isFree(level.getBlockState(belowPos))) {
                                FallingBlockEntity.fall(level, pos, newState);
                            }
                        }
                    }
                }
            }
        }

        // update neighbors
        for (BlockPos neighbor : waterNeighborsToUpdate) {
            BlockState state = level.getBlockState(neighbor);
            if (state.getFluidState().getType() == Fluids.WATER) {
                level.scheduleTick(neighbor, Fluids.WATER, Fluids.WATER.getTickDelay(level));
            }
        }

        // Batch update modified sections
        for (int i = 0; i < sectionsCount; i++) {
            if (dirtySection[i] && sections[i] != null) {
                sections[i].recalcBlockCounts();
            }
        }

        // Update heightmaps if there were changes
        if (hadChanges) {
            chunk.setUnsaved(true);
            Heightmap.primeHeightmaps(chunk, chunk.getStatus().heightmapsAfter());
        }

        return hadChanges;
    }

    /**
     * UPDATE CLIENTS
     * Sends chunk update to clients
     */
    private static void updateClients(LevelChunk chunk, ServerLevel level) {
        final ChunkPos chunkPos = chunk.getPos();

        // Refresh entire chunk for client updates
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk,
                level.getLightEngine(), null, null);

        // Packet to "forget" the chunk before resending
        ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket(chunkPos.x, chunkPos.z);

        for (ServerPlayer viewer : level.players()) {
            // Send only to players currently viewing this chunk
            if (level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).contains(viewer)) {
                // Force client to forget chunk
                viewer.connection.send(forget);
                // Wait 1 tick before resending to prevent client ignoring resend
                level.getServer().execute(() -> {
                    viewer.connection.send(packet);
                });
            }
        }
    }

    /**
     * Reset all chunks already watched
     * Used when entering a new apocalypse phase
     */
    public static void resetPlayerChunks(ServerPlayer player) {
        if (player == null || player.hasDisconnected())
            return;

        ServerLevel level = player.serverLevel();
        ChunkPos playerPos = new ChunkPos(player.blockPosition());
        int viewDistance = level.getServer().getPlayerList().getViewDistance() + 1;

        // Queue all visible chunks for reprocessing
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int cx = playerPos.x + dx;
                int cz = playerPos.z + dz;
                LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk != null) {
                    ChunkToProcess task = new ChunkToProcess(level, cx, cz);
                    DayTracker.enqueueChunk(task);
                    DayTracker.enqueueLightUpdateChunk(task);
                }
            }
        }
    }

    /**
     * Play phase-specific music for apocalypse progression
     */
    private static void playPhaseMusic(ServerPlayer player, ApocalypsePhases.Phase phase) {
        if (phase == lastPlayedPhase)
            return; // Avoid unnecessary repetition

        lastPlayedPhase = phase;

        // Stop any currently playing music
        player.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));

        // If apocalypse is over, restore normal music
        if (phase == ApocalypsePhases.Phase.PHASE_END) {
            player.playNotifySound(SoundEvents.MUSIC_GAME.value(), SoundSource.MUSIC, 1.0f, 1.0f);
            JudgementDayMod.LOGGER.info("[Apocalypse] Apocalypse ended — normal music restored.");
            return;
        }

        // Play phase-specific apocalypse music
        SoundEvent sound = phase.getMusicTrack().value();
        player.playNotifySound(sound, SoundSource.MUSIC, 1.0f, 1.0f);
        JudgementDayMod.LOGGER.info("[Apocalypse] Playing music for phase: " + phase.name());
    }

    public static void resetState() {
        daysPerPhase = ConfigManager.apocalypseMaxDays / 5;
        previousDay = 0 - daysPerPhase;
        decelerateDaysPerPhase = daysPerPhase * 2;
        lastPlayedPhase = null;

        JudgementDayMod.LOGGER.info("[Apocalypse] Generator state reset (daysPerPhase=" + daysPerPhase + ")");
    }
}