package com.creatormc.judgementdaymod.utilities;

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
    // Calculates days per phase (divides maxDays into 5 phases of 20% each), at least 1 day
    private static int daysPerPhase = Math.max(1, ConfigManager.apocalypseMaxDays / 5);
    private static int decelerateDaysPerPhase = daysPerPhase * 2; // decelerate phase frequency: double duration
    private static ApocalypsePhases.Phase lastPlayedPhase = null;

    // Chunk phase value marking a chunk as permanently done (apocalypse over)
    public static final int PHASE_DONE = 999;

    public static void handleDayEvent(ServerPlayer player) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;

        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null)
            return;

        // Calculate which phase the current day belongs to
        int currentPhaseNumber = Math.floorDiv(ConfigManager.apocalypseCurrentDay, daysPerPhase);

        // Check if we have entered a new phase (the last applied one is saved, so a
        // restart never applies the same phase twice)
        if (currentPhaseNumber > ConfigManager.lastAppliedPhase
                && ConfigManager.apocalypseCurrentDay <= ConfigManager.apocalypseMaxDays) {

            ConfigManager.lastAppliedPhase = currentPhaseNumber;

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
            resetPlayersChunks(world);

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
                    resetPlayersChunks(world);
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

            // Destruction step the world has reached (see stepForDay)
            final int currentStep = currentStep();

            final int day = ConfigManager.apocalypseCurrentDay;
            final boolean over = day >= ConfigManager.apocalypseEndDay;
            // Apocalypse progress. Once it is over the world stays as it was on its last
            // day (the ritual rewrites apocalypseMaxDays, so it cannot be used then).
            final float percent = over ? stepPercent(currentStep)
                    : day >= ConfigManager.apocalypseMaxDays ? 100
                    : Phase.toPercent(day, ConfigManager.apocalypseMaxDays);
            final boolean evaporation = over ? currentStep >= 2
                    : percent >= 40.0 || day >= ConfigManager.apocalypseMaxDays;

            ApocalypseChunkData data = ApocalypseChunkData.get(level);
            ChunkPos chunkPos = chunk.getPos();
            int savedStep = data.getPhase(chunkPos);

            // If apocalypse has ended, skip ALL processing
            if (over && savedStep == PHASE_DONE) {
                return;
            }

            // Skip if already processed for this step or later
            if (currentStep <= savedStep) {
                return;
            }

            boolean hadChanges = false;

            // PHASE 1: Water evaporation
            if (evaporation) {
                hadChanges |= evaporateWater(chunk, level, ConfigManager.minWaterEvaporationHeight,
                        level.getMaxBuildHeight(), data, currentStep);
            }

            // PHASE 2: Surface transformation, once for every step the chunk missed
            // (e.g. while nobody was near it), so it ends up like the rest of the world
            hadChanges |= transformSurface(chunk, level, percent, savedStep, currentStep);

            // Set phase to PHASE_DONE, to stop processing
            if (over) {
                data.setPhase(chunkPos, PHASE_DONE);
            } else {
                data.setPhase(chunkPos, currentStep);
            }

            // PHASE 3: Update clients (once the light around the chunk is computed, if any)
            if (hadChanges && !ChunkLightSync.resendWhenLit(chunk)) {
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
     *
     * @param data        chunk phase data, used to know which neighbours
     *                    will not be processed (and re-sent) again
     * @param currentStep step the center chunk is being processed for
     */
    private static boolean evaporateWater(LevelChunk chunk, ServerLevel level, int minBuildHeight, int maxBuildHeight,
            ApocalypseChunkData data, int currentStep) {
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

                // Indices of the sections that may contain water, top to bottom
                final int[] sectionsWithWater = new int[sections.length];
                int sectionsWithWaterCount = 0;
                for (int secIndex = maxSecIndex; secIndex >= minSecIndex; secIndex--) {
                    final LevelChunkSection section = sections[secIndex];

                    if (section == null || section.hasOnlyAir())
                        continue;

                    // Fast check: does this section contain water?
                    if (section.maybeHas(isEvaporable)) {
                        sectionsWithWater[sectionsWithWaterCount++] = secIndex;
                    }
                }

                // If no section has water, skip this chunk entirely
                if (sectionsWithWaterCount == 0)
                    continue;

                // A neighbour already processed for this phase is never re-sent, so the
                // water removed here (e.g. water that flowed back in from a chunk processed
                // later) must be sent to clients block by block, otherwise they keep seeing
                // "ghost" water walls that do not exist on the server.
                final boolean notifyClients = (offsetX != 0 || offsetZ != 0)
                        && currentStep <= data.getPhase(targetChunk.getPos());
                boolean chunkChanged = false;
                final LightChecks lightChecks = new LightChecks(level, targetChunkX, targetChunkZ, false);
                Heightmap[] heightmaps = null;

                // Iterate ONLY over sections that contain water
                for (int i = 0; i < sectionsWithWaterCount; i++) {
                    final int secIndex = sectionsWithWater[i];
                    final LevelChunkSection section = sections[secIndex];

                    if (section == null || section.hasOnlyAir())
                        continue;

                    final int sectionWorldY = (secIndex + minSection) << 4;
                    boolean sectionModified = false;

                    // isEvaporable is a pure function of the state and a section holds long
                    // runs of the same state: remember the last answer
                    BlockState lastState = null;
                    boolean lastEvaporable = false;

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

                                if (state != lastState) {
                                    lastState = state;
                                    lastEvaporable = Analyzer.isEvaporable(state);
                                }
                                if (!lastEvaporable)
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

                                // The block is replaced without the usual block callbacks: drop its
                                // block entity too (e.g. waterlogged chests), or it stays orphaned
                                if (state.hasBlockEntity()) {
                                    removeBlockEntity(targetChunk, mutablePos.immutable());
                                }

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

                                chunkChanged = true;
                                if (notifyClients) {
                                    level.getChunkSource().blockChanged(mutablePos);
                                }

                                // Heightmaps follow the change, as LevelChunk#setBlockState does
                                if (heightmaps == null) {
                                    heightmaps = heightmaps(targetChunk);
                                }
                                for (Heightmap heightmap : heightmaps) {
                                    heightmap.update(localX, worldY, localZ, airState);
                                }

                                // Light management (checks queued below, all at once)
                                if (LightEngine.hasDifferentLightProperties(targetChunk, mutablePos, oldState,
                                        airState)) {
                                    targetChunk.getSkyLightSources().update(targetChunk, localX, worldY, localZ);
                                    lightChecks.add(mutablePos);
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
                }

                // Light checks of this chunk: queued, then forwarded to clients once computed
                if (lightChecks.submit()) {
                    ChunkLightSync.lightQueued(level, targetChunkX, targetChunkZ);
                }
            }
        }

        return hadChanges;
    }

    /**
     * SURFACE TRANSFORMATION
     * Converts surface blocks to ash, fire, or air based on apocalypse progress.
     *
     * Every step after savedStep up to currentStep is applied: a column can be
     * transformed once per step, with the chance of that step (the chance of the
     * current progress for the last one). A chunk processed on time only misses the
     * current step, so this is a single pass, exactly as before.
     */
    private static boolean transformSurface(LevelChunk chunk, ServerLevel level, float percent, int savedStep,
            int currentStep) {
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

        // Chance of every step to apply, oldest first
        final int firstStep = Math.max(savedStep + 1, 0);
        final int stepCount = currentStep - firstStep + 1;
        final float[] thresholds = new float[Math.max(stepCount, 0)];
        for (int i = 0; i < thresholds.length; i++) {
            final int step = firstStep + i;
            thresholds[i] = step == currentStep ? percent / 100f : stepPercent(step) / 100f;
        }

        // Track which sections were modified
        final boolean[] dirtySection = new boolean[sectionsCount];
        boolean hadChanges = false;
        // Sky light checks of the changed blocks (shadows), queued below all at once.
        // The block light is left as it always was (the fires lit here do not glow), and so
        // is the light around those fires: they burn out within moments and vanilla checks
        // their spot then, checking them now too would only double that light work.
        final LightChecks lightChecks = new LightChecks(level, chunk.getPos().x, chunk.getPos().z, true);
        // A falling block left air behind: vanilla queued its light check
        boolean blockFell = false;

        // Cache heightmap for fast surface access
        final Heightmap wsHM = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        // Heightmaps follow every change, as LevelChunk#setBlockState does
        final Heightmap[] heightmaps = heightmaps(chunk);

        // Single pass over all columns
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // Highest non air block of the column, read from the heightmap for the first
                // step only: later steps follow the column themselves (see highestNonAir)
                int topY = Integer.MIN_VALUE;

                for (int stepIndex = 0; stepIndex < thresholds.length; stepIndex++) {
                    final float apocalypseThreshold = thresholds[stepIndex];

                    // A step that cannot apply (0%) is skipped without drawing
                    if (stepIndex < thresholds.length - 1 && apocalypseThreshold <= 0f)
                        continue;

                    // Random transformation based on apocalypse progress
                    if (random.nextFloat() > apocalypseThreshold)
                        continue;

                    if (topY == Integer.MIN_VALUE) {
                        topY = wsHM.getFirstAvailable(lx, lz) - 1;
                    } else {
                        topY = highestNonAir(sections, minSection, lx, topY, lz);
                    }
                    if (topY < minBuildHeight)
                        break;

                    int secIndex = (topY >> 4) - minSection;
                    if (secIndex < 0 || secIndex >= sectionsCount)
                        break;

                    LevelChunkSection section = sections[secIndex];
                    if (section == null)
                        break;

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

                            BlockPos pos = new BlockPos(startX + lx, targetY, startZ + lz);

                            // The block is replaced without the usual block callbacks: drop its
                            // block entity too, or it would stay orphaned in the chunk
                            if (oldState.hasBlockEntity()) {
                                removeBlockEntity(chunk, pos);
                            }

                            targetSection.setBlockState(lx, finalLy, lz, newState, false);
                            dirtySection[finalSecIndex] = true;
                            hadChanges = true;
                            for (Heightmap heightmap : heightmaps) {
                                heightmap.update(lx, targetY, lz, newState);
                            }

                            // Light management (e.g. water or snow gone, ash in place of a slab)
                            if (newBlock != fireBlock
                                    && LightEngine.hasDifferentLightProperties(chunk, pos, oldState, newState)) {
                                chunk.getSkyLightSources().update(chunk, lx, targetY, lz);
                                lightChecks.add(pos);
                            }

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
                                    blockFell = true;
                                }
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

        // Save the chunk if there were changes
        if (hadChanges) {
            chunk.setUnsaved(true);
        }

        // Light checks queued: forward the final light to clients once computed
        final boolean lightQueued = lightChecks.submit();
        if (lightQueued || blockFell) {
            ChunkLightSync.lightQueued(level, chunk.getPos().x, chunk.getPos().z);
        }

        return hadChanges;
    }

    /**
     * Highest non air block of the column at or below fromY (WORLD_SURFACE rule),
     * read straight from the sections. Integer.MIN_VALUE + 1 if there is none.
     * Used between missed steps: steps are days apart and the fire lit by a step has
     * burned out before the next one, so fire counts as air here.
     */
    private static int highestNonAir(LevelChunkSection[] sections, int minSection, int lx, int fromY, int lz) {
        for (int y = fromY; y >= minSection << 4; y--) {
            final int secIndex = (y >> 4) - minSection;
            if (secIndex >= sections.length)
                continue;
            final LevelChunkSection section = sections[secIndex];
            if (section == null || section.hasOnlyAir()) {
                // Jump to the top of the section below
                y = ((y >> 4) << 4);
                continue;
            }
            final BlockState state = section.getBlockState(lx, y & 15, lz);
            if (!state.isAir() && !state.is(Blocks.FIRE))
                return y;
        }
        return Integer.MIN_VALUE + 1;
    }

    /**
     * The heightmaps of the chunk, kept up to date block by block while editing its
     * sections (the same updates LevelChunk#setBlockState makes). Rebuilding them from
     * scratch afterwards gives the same result but scans every column of the chunk.
     */
    private static Heightmap[] heightmaps(LevelChunk chunk) {
        final java.util.Set<Heightmap.Types> types = chunk.getStatus().heightmapsAfter();
        final Heightmap[] maps = new Heightmap[types.size()];
        int i = 0;
        for (Heightmap.Types type : types) {
            maps[i++] = chunk.getOrCreateHeightmapUnprimed(type);
        }
        return maps;
    }

    /**
     * Removes the block entity at pos (also one still waiting to be loaded).
     */
    private static void removeBlockEntity(LevelChunk chunk, BlockPos pos) {
        // Loads a pending block entity, if any, so that it is removed as well
        chunk.getBlockEntity(pos);
        chunk.removeBlockEntity(pos);
    }

    /**
     * DESTRUCTION STEPS
     * Step 0-5: one per apocalypse phase (as the chunk phase was before).
     * Step 6+: once the apocalypse is active, one more every decelerateDaysPerPhase
     * days, on the same days the visible chunks are reset (ash goes one block deeper).
     */
    static int stepForDay(int day) {
        final int maxDays = ConfigManager.apocalypseMaxDays;
        if (day < maxDays) {
            final float percent = Phase.toPercent(day, maxDays);
            return Math.min(5, (int) (percent / 20.0f));
        }
        // Same value as decelerateDaysPerPhase, computed from the config so that it is
        // also right while the spawn area is generated, before resetState() runs
        final int decelerate = Math.max(1, maxDays / 5) * 2;
        // Older versions never went past step 5: a world they already took past the
        // countdown counts the steps from the day it was first run with this version
        // (nothing is applied retroactively)
        final int origin = ConfigManager.stepOriginDay;
        final int stepsFrom = origin != ConfigManager.UNSET ? Math.max(maxDays, origin) : maxDays;
        return 5 + Math.max(0, Math.floorDiv(day, decelerate) - Math.floorDiv(stepsFrom, decelerate));
    }

    /**
     * Step the world has reached. It stops growing when the apocalypse is over: at the
     * step of its last day after a natural end, at the step saved by the ritual
     * (ConfigManager.lastApocalypseStep) when the ritual ended it.
     */
    public static int currentStep() {
        if (ConfigManager.apocalypseCurrentDay < ConfigManager.apocalypseEndDay) {
            return stepForDay(ConfigManager.apocalypseCurrentDay);
        }
        final int ritualStep = ConfigManager.lastApocalypseStep;
        return ritualStep != ConfigManager.UNSET ? ritualStep : stepForDay(ConfigManager.apocalypseEndDay - 1);
    }

    /**
     * Step to record for a chunk being generated. Without the apocalypse the chunk is
     * untouched (0) and catches up on every step. During the apocalypse it is generated
     * with three layers of ash, about what the five phases do to the rest of the world:
     * it gets one pass for the current phase, as before, or only the steps after the
     * phases once the apocalypse has gone past them.
     * Called from world generation threads.
     */
    public static int stepForNewChunk() {
        if (!ConfigManager.apocalypseActive) {
            return 0;
        }
        final int step = currentStep();
        return step <= 5 ? Math.max(0, step - 1) : 5;
    }

    /**
     * True if a chunk with this saved step has missed at least one step the world went
     * through (unknown chunks, -1, are not considered).
     */
    public static boolean missedSteps(int savedStep) {
        return savedStep >= 0 && savedStep != PHASE_DONE && savedStep < currentStep();
    }

    /**
     * Apocalypse percent at which a past step was applied to the chunks in view.
     */
    private static int stepPercent(int step) {
        return Math.min(100, Math.max(0, step) * 20);
    }

    /**
     * UPDATE CLIENTS
     * Sends chunk update to clients
     */
    static void updateClients(LevelChunk chunk, ServerLevel level) {
        final ChunkPos chunkPos = chunk.getPos();

        // Players currently viewing this chunk
        final List<ServerPlayer> viewers = level.getChunkSource().chunkMap.getPlayers(chunkPos, false);
        if (viewers.isEmpty())
            return;

        // Refresh entire chunk for client updates
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk,
                level.getLightEngine(), null, null);

        // Packet to "forget" the chunk before resending
        ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket(chunkPos.x, chunkPos.z);

        for (ServerPlayer viewer : level.players()) {
            // Send only to players currently viewing this chunk
            if (viewers.contains(viewer)) {
                // Force client to forget chunk
                viewer.connection.send(forget);
                // Resend once the current tick is over, after the forget packet
                level.getServer().execute(() -> {
                    viewer.connection.send(packet);
                });
            }
        }
    }

    /**
     * Reset the chunks around every player in the overworld (each chunk once)
     * Used when entering a new apocalypse phase or destruction step.
     * Only loaded chunks are queued: loading the others here froze the server for
     * seconds, and they catch up on every missed step as soon as a player sees them.
     */
    public static void resetPlayersChunks(ServerLevel overworld) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet queued = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        int viewDistance = overworld.getServer().getPlayerList().getViewDistance() + 1;
        for (ServerPlayer player : overworld.players()) {
            if (player.hasDisconnected())
                continue;
            ChunkPos playerPos = new ChunkPos(player.blockPosition());
            for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                    int cx = playerPos.x + dx;
                    int cz = playerPos.z + dz;
                    if (queued.add(ChunkPos.asLong(cx, cz)) && overworld.hasChunk(cx, cz)) {
                        DayTracker.enqueueChunk(new ChunkToProcess(overworld, cx, cz));
                    }
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
        daysPerPhase = Math.max(1, ConfigManager.apocalypseMaxDays / 5);
        decelerateDaysPerPhase = daysPerPhase * 2;
        lastPlayedPhase = null;

        final int day = ConfigManager.apocalypseCurrentDay;
        final int phaseOfDay = Math.floorDiv(day, daysPerPhase);

        // Configs written by older versions don't have the last applied phase: every phase
        // up to the current day was applied when it was reached (none before day 1).
        // A phase past the current day means the day was moved back by hand: same rule.
        if (ConfigManager.lastAppliedPhase == ConfigManager.UNSET || ConfigManager.lastAppliedPhase > phaseOfDay) {
            ConfigManager.lastAppliedPhase = day > 0 ? phaseOfDay : -1;
        }

        // The step saved by the ritual only means something once the apocalypse is over
        // (e.g. it is left over from an apocalypse played in another world)
        if (day < ConfigManager.apocalypseEndDay) {
            ConfigManager.lastApocalypseStep = ConfigManager.UNSET;
        }

        // A config written by an older version past the countdown: its chunks stopped at
        // step 5, count the later steps from today. A new countdown has no origin.
        if (day < ConfigManager.apocalypseMaxDays) {
            ConfigManager.stepOriginDay = ConfigManager.UNSET;
        } else if (ConfigManager.legacyConfig && ConfigManager.stepOriginDay == ConfigManager.UNSET) {
            ConfigManager.stepOriginDay = day;
        }
        if (ConfigManager.legacyConfig) {
            // Write the new values now, so the next start does not take it for an old config
            ConfigManager.legacyConfig = false;
            ConfigManager.save();
        }

        JudgementDayMod.LOGGER.info("[Apocalypse] Generator state reset (daysPerPhase=" + daysPerPhase + ")");
    }
}