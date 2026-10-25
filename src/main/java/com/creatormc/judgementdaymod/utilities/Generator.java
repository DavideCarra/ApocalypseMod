package com.creatormc.judgementdaymod.utilities;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.creatormc.judgementdaymod.handlers.DayTracker;
import com.creatormc.judgementdaymod.handlers.NetworkHandler;
import com.creatormc.judgementdaymod.handlers.UnattendedChunkHandler;
import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.models.PhaseTitlePacket;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.setup.ModBlocks;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import io.netty.util.internal.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Generator {
    // Calculates days per phase (divides maxDays into 5 phases of 20% each), at least 1 day
    private static int daysPerPhase = Math.max(1, ConfigManager.apocalypseMaxDays / 5);
    private static int decelerateDaysPerPhase = daysPerPhase * 2; // decelerate phase frequency: double duration
    private static ApocalypsePhases.Phase lastPlayedPhase = null;

    // Chunk phase value marking a chunk as permanently done (apocalypse over)
    public static final int PHASE_DONE = 999;

    public static void handleDayEvent() {
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
            lowerHeights(currentPhaseNumber);

            ConfigManager.save();
            resetPlayersChunks(world);

            // Calculate percentage for Phase
            int percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);
            Phase currentPhase = Phase.getPhaseForPercent(percent);
            NetworkHandler.sendToAll(new PhaseTitlePacket(currentPhase));

            // Start music for the new phase
            if (ConfigManager.apocalypseCurrentDay <= ConfigManager.apocalypseEndDay) {
                playPhaseMusic(server, currentPhase);
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
                NetworkHandler.sendToAll(new PhaseTitlePacket(Phase.PHASE_END));
            }
        }
    }

    /**
     * Lowers the damage and water heights for the phase that starts.
     */
    private static void lowerHeights(int phase) {
        // Current damage and water heights
        int baseDamageHeight = ConfigManager.minDamageHeight;
        int baseWaterHeight = ConfigManager.minWaterEvaporationHeight;

        // Calculate new heights based on phase
        int newDamageHeight = baseDamageHeight - (phase * (baseDamageHeight / 5));
        int newWaterHeight = baseWaterHeight - (phase * (baseWaterHeight / 5));

        // Ensure they do not go below minimum
        ConfigManager.minDamageHeight = Math.max(newDamageHeight, -20);
        ConfigManager.minWaterEvaporationHeight = Math.max(newWaterHeight, -20);
    }

    // Work of a catch up, in chunks processed on time: steps applied to a chunk, and
    // columns whose falling ash lands (the heavy part), that cost about one chunk
    private static final int CATCH_UP_STEPS_PER_CHUNK = 16;
    private static final int LANDING_COLUMNS_PER_CHUNK = 16;
    // Blocks burned below the block a step reached (whole trees, see transformSurface)
    // that cost about one chunk
    private static final int BURNT_BLOCKS_PER_CHUNK = 1024;
    private static int lastBurntBlocks;
    // Columns of the last chunk transformed whose falling ash lands after the pass
    private static int lastLandingColumns;

    /**
     * Processes the chunk for the step the world has reached.
     *
     * @return the work it took, in chunks processed for one step: 1 for a chunk processed
     *         on time (or skipped), more for a catch up of many steps (see DayTracker)
     */
    @SuppressWarnings("resource")
    public static float processChunk(ChunkToProcess chunkInfo) {
        try {
            final ServerLevel level = chunkInfo.getLevel();
            final int chunkX = chunkInfo.getChunkX();
            final int chunkZ = chunkInfo.getChunkZ();

            if (!level.getServer().isSameThread() || !level.hasChunk(chunkX, chunkZ)) {
                return 1f;
            }

            // Process only overworld
            if (level.dimension() != Level.OVERWORLD) {
                return 1f;
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

            // Skip if already processed for this step or later (or done for good after the
            // end). Snow and ice melt from the start of the apocalypse on: such a chunk is
            // looked at for them once (none forms again then, see BiomeMixin)
            if ((over && savedStep == PHASE_DONE) || currentStep <= savedStep) {
                if (apocalypseStarted() && meltChecked.add(chunkPos.toLong())) {
                    meltOnly(chunk, level);
                }
                return 1f;
            }

            // Steps applied: every one after the saved step (step 0 never applies)
            final int firstStep = Math.max(savedStep + 1, 0);
            int steps = currentStep - firstStep + 1;
            if (firstStep == 0 && steps > 1)
                steps--;

            boolean hadChanges = false;

            // PHASE 1: Water evaporation
            if (evaporation) {
                hadChanges |= evaporateWater(chunk, level, ConfigManager.minWaterEvaporationHeight,
                        level.getMaxBuildHeight(), data, currentStep);
            }

            // PHASE 2: Surface transformation, once for every step the chunk missed
            // (e.g. while nobody was near it), so it ends up like the rest of the world
            lastLandingColumns = 0;
            lastBurntBlocks = 0;
            hadChanges |= transformSurface(chunk, level, percent, savedStep, currentStep, over);
            if (apocalypseStarted()) {
                meltChecked.add(chunkPos.toLong());
            }
            final float work = 1f + (steps - 1) / (float) CATCH_UP_STEPS_PER_CHUNK
                    + lastLandingColumns / (float) LANDING_COLUMNS_PER_CHUNK
                    + lastBurntBlocks / (float) BURNT_BLOCKS_PER_CHUNK;

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

            // Mobs judged when the chunk was loaded, before it caught up (e.g. under water
            // that has just evaporated), are judged again once its light is computed
            UnattendedChunkHandler.chunkCaughtUp(level, chunkPos.toLong());
            return work;

        } catch (Exception e) {
            System.err.println("[Apocalypse] Error processing chunk: " + e.getMessage());
            e.printStackTrace();
            return 1f;
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
                    // Heightmaps only need rebuilding if a block of THIS chunk changed:
                    // on an untouched chunk they are already exact and priming is a no-op
                    if (chunkChanged) {
                        Heightmap.primeHeightmaps(targetChunk, targetChunk.getStatus().heightmapsAfter());
                    }
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
     *
     * From the start of the apocalypse on, the snow and ice on top of each column melt
     * first (see meltColumn).
     *
     * @param over the apocalypse is over: what would catch fire is left empty instead, its
     *             fires burned out long ago (a chunk processed on time is never processed
     *             again then)
     */
    private static boolean transformSurface(LevelChunk chunk, ServerLevel level, float percent, int savedStep,
            int currentStep, boolean over) {
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
        // What a burning block becomes
        final BlockState burntState = over ? airState : fireState;
        final Block burntBlock = over ? null : fireBlock;

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
        // Catching up on several steps at once (step 0 never applies): the ash that falls
        // lands at once (see landFalls)
        int applicableSteps = 0;
        for (int i = 0; i < thresholds.length; i++) {
            if (i == thresholds.length - 1 || thresholds[i] > 0f)
                applicableSteps++;
        }
        final boolean catchUp = applicableSteps > 1;
        // Falls of the columns of this chunk, landed after the whole pass (see landFalls)
        List<List<BlockPos>> chunkFalls = null;

        // Cache heightmap for fast surface access
        final Heightmap wsHM = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);

        // Snow and ice melt from the start of the apocalypse on (see meltColumn)
        final boolean melt = apocalypseStarted() && hasMeltable(sections);

        // Single pass over all columns
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (melt && meltColumn(chunk, level, sections, wsHM, lx, lz, dirtySection, lightChecks)) {
                    hadChanges = true;
                }

                // Highest non air block of the column, read from the heightmap for the first
                // step only: later steps follow the column themselves (see highestNonAir)
                int topY = Integer.MIN_VALUE;
                // Below the block changed by the previous step: everything above it down to
                // there is air, ash or fire, so the search for the next solid block resumes
                // there instead of going through the whole ash column again
                int searchFrom = Integer.MAX_VALUE;
                // Tick of the last ash placed in the column, not scheduled yet (see below)
                BlockPos pendingAshTick = null;
                // Ash of this column that fell during a catch up, top to bottom
                List<BlockPos> falls = null;

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
                    // The block burns (see below)
                    boolean burning = false;

                    // If current block is skippable (ash/air), find first solid block below
                    if (Analyzer.isSkippable(currentBlock)) {
                        boolean foundSolid = false;

                        for (int y = Math.min(topY - 1, searchFrom); y > minBuildHeight; y--) {
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
                                    newState = burntState;
                                    newBlock = burntBlock;
                                    burning = true;
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
                            newState = burntState;
                            newBlock = burntBlock;
                            burning = true;
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

                            // Block below an ash, read before anything else happens at pos (only
                            // the removal of a block entity could change it, so not then): the
                            // same block the falling check below would read
                            BlockState belowState = newBlock == ashBlock && !oldState.hasBlockEntity()
                                    ? level.getBlockState(pos.below())
                                    : null;

                            // The tick of the ash placed by the previous step, kept back until now:
                            // dropped if this step puts an ash right under it that stays there,
                            // since the tick would find a full block below and do nothing (this
                            // leaves one tick per column after a catch up of many steps, as a
                            // single step does). Otherwise scheduled, in the same order as before
                            if (pendingAshTick != null) {
                                if (belowState == null || targetY != pendingAshTick.getY() - 1
                                        || FallingBlock.isFree(belowState)) {
                                    level.scheduleTick(pendingAshTick, ashBlock, 1);
                                }
                                pendingAshTick = null;
                            }

                            // The block is replaced without the usual block callbacks: drop its
                            // block entity too, or it would stay orphaned in the chunk
                            if (oldState.hasBlockEntity()) {
                                removeBlockEntity(chunk, pos);
                            }

                            // The fire takes the whole part of the column that burns at once, not
                            // a block per step (a tall tree would outlast the apocalypse): down
                            // from here to the first block that does not burn, passing the air,
                            // ash and fire on the way. What burned is left empty and the fire
                            // stays at the bottom, on what stopped it (none after the end). A
                            // column with nothing more to burn below is as before
                            int fireY = targetY;
                            if (burning) {
                                for (int y = targetY - 1; y > minBuildHeight; y--) {
                                    final int s = (y >> 4) - minSection;
                                    if (s < 0 || s >= sectionsCount || sections[s] == null)
                                        break;
                                    final BlockState st = sections[s].getBlockState(lx, y & 15, lz);
                                    if (Analyzer.isSkippable(st)) {
                                        // Ash left hanging falls
                                        if (st.getBlock() instanceof FallingBlock fallingBlock) {
                                            level.scheduleTick(new BlockPos(startX + lx, y, startZ + lz), fallingBlock, 1);
                                        }
                                        continue;
                                    }
                                    if (!Analyzer.canBurn(st, null, null))
                                        break;
                                    final BlockPos burnt = new BlockPos(startX + lx, y, startZ + lz);
                                    if (st.hasBlockEntity()) {
                                        removeBlockEntity(chunk, burnt);
                                    }
                                    sections[s].setBlockState(lx, y & 15, lz, airState, false);
                                    dirtySection[s] = true;
                                    lastBurntBlocks++;
                                    // Its own update, top to bottom: the block on top may be one
                                    // that lets the light through (a vine), which updates nothing
                                    if (LightEngine.hasDifferentLightProperties(chunk, burnt, st, airState)) {
                                        chunk.getSkyLightSources().update(chunk, lx, y, lz);
                                        lightChecks.add(burnt);
                                    }
                                    fireY = y;
                                }
                                if (fireY != targetY) {
                                    newState = airState;
                                    newBlock = null;
                                    // What rested on top of it falls
                                    final BlockState aboveState = level.getBlockState(pos.above());
                                    if (aboveState.getBlock() instanceof FallingBlock fallingBlock) {
                                        level.scheduleTick(pos.above(), fallingBlock, 1);
                                    }
                                    if (burntBlock == fireBlock) {
                                        final int fireSec = (fireY >> 4) - minSection;
                                        final BlockPos firePos = new BlockPos(startX + lx, fireY, startZ + lz);
                                        sections[fireSec].setBlockState(lx, fireY & 15, lz, fireState, false);
                                        level.scheduleTick(firePos, fireBlock, 1);
                                    }
                                }
                            }

                            targetSection.setBlockState(lx, finalLy, lz, newState, false);
                            dirtySection[finalSecIndex] = true;
                            hadChanges = true;
                            searchFrom = fireY - 1;

                            // Light management (e.g. water or snow gone, ash in place of a slab)
                            if (newBlock != fireBlock
                                    && LightEngine.hasDifferentLightProperties(chunk, pos, oldState, newState)) {
                                chunk.getSkyLightSources().update(chunk, lx, targetY, lz);
                                lightChecks.add(pos);
                            }

                            // Schedule ticks inline
                            if (newBlock == ashBlock) {
                                if (belowState == null) {
                                    belowState = level.getBlockState(pos.below());
                                }
                                // Handle falling blocks
                                if (FallingBlock.isFree(belowState)) {
                                    level.scheduleTick(pos, ashBlock, 1);
                                    if (catchUp) {
                                        // What FallingBlockEntity.fall does to the block; the
                                        // falling ash itself comes after the column (see landFalls)
                                        level.setBlock(pos, newState.getFluidState().createLegacyBlock(), 3);
                                        if (falls == null) {
                                            falls = new ArrayList<>(4);
                                        }
                                        falls.add(pos);
                                    } else {
                                        FallingBlockEntity.fall(level, pos, newState);
                                    }
                                    blockFell = true;
                                } else {
                                    // Scheduled by the next step of the column or after its last
                                    pendingAshTick = pos;
                                }
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
                        }
                    }
                }

                if (pendingAshTick != null) {
                    level.scheduleTick(pendingAshTick, ashBlock, 1);
                }
                if (falls != null) {
                    if (chunkFalls == null) {
                        chunkFalls = new ArrayList<>();
                    }
                    chunkFalls.add(falls);
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

        // The falling ash of a catch up lands after the pass, as the falling blocks did
        // (after the whole batch of chunks when there is one), before the light markers
        if (chunkFalls != null) {
            lastLandingColumns = chunkFalls.size();
            for (List<BlockPos> falls : chunkFalls) {
                pendingFalls.add(new PendingFalls(level, falls, ashState));
            }
            if (batchDepth == 0) {
                landPendingFalls();
            }
        }

        // Light checks queued: forward the final light to clients once computed
        final boolean lightQueued = lightChecks.submit();
        if (lightQueued || blockFell) {
            ChunkLightSync.lightQueued(level, chunk.getPos().x, chunk.getPos().z);
        }

        return hadChanges;
    }

    /**
     * The apocalypse has started: the preparation days and the delay before it are over.
     */
    public static boolean apocalypseStarted() {
        return ConfigManager.apocalypseCurrentDay >= 0 && ConfigManager.startDelayDays <= 0;
    }

    // Chunks already looked at for snow and ice since the server started (main thread)
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet meltChecked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /**
     * The chunk has snow or ice somewhere: their palettes tell at once.
     */
    private static boolean hasMeltable(LevelChunkSection[] sections) {
        for (LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir() && section.maybeHas(Analyzer::isMeltable)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The snow and ice on top of the column melt, down to the first block that is
     * neither: ice that touches water (a frozen sea, the part of an iceberg in the sea)
     * turns into water, the rest into air.
     *
     * @return true if a block changed
     */
    private static boolean meltColumn(LevelChunk chunk, ServerLevel level, LevelChunkSection[] sections, Heightmap wsHM,
            int lx, int lz, boolean[] dirtySection, LightChecks lightChecks) {
        final int minSection = chunk.getMinSection();
        final int sectionsCount = sections.length;
        final BlockState airState = Blocks.AIR.defaultBlockState();
        boolean changed = false;
        int y = wsHM.getFirstAvailable(lx, lz) - 1;
        while (true) {
            final int sec = (y >> 4) - minSection;
            if (sec < 0 || sec >= sectionsCount || sections[sec] == null)
                break;
            final BlockState old = sections[sec].getBlockState(lx, y & 15, lz);
            if (!Analyzer.isMeltable(old))
                break;
            final BlockPos pos = new BlockPos((chunk.getPos().x << 4) + lx, y, (chunk.getPos().z << 4) + lz);
            final BlockState below = chunk.getBlockState(pos.below());
            final boolean toWater = Analyzer.isIce(old)
                    && (below.is(Blocks.WATER) || touchesWater(level, chunk, pos));
            final BlockState melted = toWater ? Blocks.WATER.defaultBlockState() : airState;
            sections[sec].setBlockState(lx, y & 15, lz, melted, false);
            dirtySection[sec] = true;
            changed = true;
            if (LightEngine.hasDifferentLightProperties(chunk, pos, old, melted)) {
                chunk.getSkyLightSources().update(chunk, lx, y, lz);
                lightChecks.add(pos);
            }
            if (toWater) {
                level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
            } else if (below.hasProperty(BlockStateProperties.SNOWY) && below.getValue(BlockStateProperties.SNOWY)) {
                // Grass, podzol or mycelium no longer under snow (vanilla updates it
                // through the shape update the direct edit skips)
                final int belowSec = ((y - 1) >> 4) - minSection;
                if (belowSec >= 0 && belowSec < sectionsCount && sections[belowSec] != null) {
                    sections[belowSec].setBlockState(lx, (y - 1) & 15, lz,
                            below.setValue(BlockStateProperties.SNOWY, false), false);
                    dirtySection[belowSec] = true;
                }
            }
            y--;
        }
        return changed;
    }

    /**
     * Melts the snow and ice on top of a chunk that is not transformed now (it already
     * had the current step), and sends it again if anything changed.
     */
    private static void meltOnly(LevelChunk chunk, ServerLevel level) {
        final LevelChunkSection[] sections = chunk.getSections();
        if (!hasMeltable(sections))
            return;
        final boolean[] dirtySection = new boolean[sections.length];
        final LightChecks lightChecks = new LightChecks(level, chunk.getPos().x, chunk.getPos().z, true);
        final Heightmap wsHM = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        boolean changed = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                changed |= meltColumn(chunk, level, sections, wsHM, lx, lz, dirtySection, lightChecks);
            }
        }
        if (!changed)
            return;
        for (int i = 0; i < sections.length; i++) {
            if (dirtySection[i] && sections[i] != null) {
                sections[i].recalcBlockCounts();
            }
        }
        chunk.setUnsaved(true);
        Heightmap.primeHeightmaps(chunk, chunk.getStatus().heightmapsAfter());
        if (lightChecks.submit()) {
            ChunkLightSync.lightQueued(level, chunk.getPos().x, chunk.getPos().z);
        }
        if (!ChunkLightSync.resendWhenLit(chunk)) {
            updateClients(chunk, level);
        }
    }

    /**
     * Water next to the block, on one of its four sides. A side in a chunk that is not
     * loaded counts as dry (never loaded for this).
     */
    private static boolean touchesWater(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            final BlockPos side = pos.relative(dir);
            final int cx = SectionPos.blockToSectionCoord(side.getX());
            final int cz = SectionPos.blockToSectionCoord(side.getZ());
            final LevelChunk sideChunk = cx == chunk.getPos().x && cz == chunk.getPos().z ? chunk
                    : level.getChunkSource().getChunkNow(cx, cz);
            if (sideChunk != null && sideChunk.getBlockState(side).is(Blocks.WATER))
                return true;
        }
        return false;
    }

    // Chunks are being processed as a batch (see beginBatch)
    private static int batchDepth = 0;

    private record PendingFalls(ServerLevel level, List<BlockPos> falls, BlockState state) {
    }

    // Falling ash of the batch, landed when the batch ends
    private static final List<PendingFalls> pendingFalls = new ArrayList<>();

    /**
     * Chunks processed until endBatch form one batch: the falling ash of their catch ups
     * lands once all of them are processed, as the falling blocks did (ticks later).
     * Server thread only.
     */
    public static void beginBatch() {
        batchDepth++;
    }

    /**
     * Ends the batch started by beginBatch, landing its falling ash. Called before the
     * light checks of the batch are queued (LightChecks.end), so that the processed chunks
     * are sent again with the light of the landed ash too.
     */
    public static void endBatch() {
        if (batchDepth > 0 && --batchDepth == 0) {
            landPendingFalls();
        }
    }

    /** Drops the falls not landed yet (server stopping). */
    public static void clearPendingFalls() {
        pendingFalls.clear();
        batchDepth = 0;
    }

    private static void landPendingFalls() {
        for (int i = 0; i < pendingFalls.size(); i++) {
            final PendingFalls p = pendingFalls.get(i);
            try {
                landFalls(p.level(), p.falls(), p.state());
            } catch (Exception e) {
                System.err.println("[Apocalypse] Error landing falling ash: " + e.getMessage());
                e.printStackTrace();
            }
        }
        pendingFalls.clear();
    }

    /**
     * The ash of a column that fell during a catch up of several steps. A catch up
     * compresses days of collapses into one moment: an ash that drops into a cave takes
     * the whole ash column above it down with it, one falling block entity after the
     * other, and tens of thousands of falling blocks at once froze the server (their
     * collision checks grow with the square of their number). Here, when that column
     * would simply drop through air onto a full block, it is put where the falling blocks
     * would land, in the order they land (the lowest first, each on the one before): the
     * same blocks placed the same way (setBlock with updates) after the same ground hit
     * vibrations, only without the seconds of flight. All the ash is kept, as when the
     * collapses come on different days (compressed into one moment, a falling block that
     * lands on one leaving the same tick drops as an item). Only the cells that change
     * are written: the ones the column fills below and the ones it leaves empty on top. The
     * ash cells in between were emptied and filled again by the falling blocks, which
     * told their neighbours twice: this is only skipped when no neighbour reacts to it
     * (blocks without shape or neighbour updates, air; plain falling blocks get the tick
     * the update would have given them).
     * Any other case (water, plants, fire or anything else on the way or below or next to
     * those cells, a block that reacts to what lands on it, an entity a falling block
     * collides with, something above the column that would fall into it through fire or
     * water, no ground) falls as before, and so does everything above it in the column.
     *
     * @param falls positions the ash fell from, top to bottom (already air)
     */
    private static void landFalls(ServerLevel level, List<BlockPos> falls, BlockState state) {
        final Block ashBlock = state.getBlock();
        final int x = falls.get(0).getX();
        final int z = falls.get(0).getZ();
        final int minY = level.getMinBuildHeight();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final List<BlockState> column = new ArrayList<>();
        final List<BlockPos> pendingTicks = new ArrayList<>();

        // Lowest fall not landed yet
        int next = falls.size() - 1;
        while (next >= 0) {
            final int startY = falls.get(next).getY();

            // Ground: the first block below, through air only
            int groundY = startY - 1;
            BlockState ground = null;
            while (groundY >= minY) {
                ground = level.getBlockState(pos.set(x, groundY, z));
                if (!ground.isAir())
                    break;
                groundY--;
            }
            if (ground == null || groundY < minY || FallingBlock.isFree(ground)
                    || !ground.isCollisionShapeFullBlock(level, pos) || reactsToLanding(ground.getBlock())
                    || !state.canSurvive(level, pos.set(x, groundY + 1, z)))
                break;

            // What comes down, bottom to top: the fallen ash, the ash above it (each one
            // falls once the one below has left), and the same again for a fall higher up
            // whose ash drops onto this column through air
            column.clear();
            column.add(state);
            int above = next - 1;
            int y = startY + 1;
            while (true) {
                final BlockState current = level.getBlockState(pos.set(x, y, z));
                if (current.is(ashBlock)) {
                    column.add(current);
                    y++;
                } else if (above >= 0 && current.isAir() && onlyAir(level, pos, x, y + 1, falls.get(above).getY(), z)) {
                    column.add(state);
                    y = falls.get(above).getY() + 1;
                    above--;
                } else {
                    break;
                }
            }
            final int topY = y - 1;

            // Above the column: anything resting on fire, water or plants right above it
            // could drop into them while the column comes down
            int aboveY = topY + 1;
            BlockState aboveState = level.getBlockState(pos.set(x, aboveY, z));
            while (!aboveState.isAir() && FallingBlock.isFree(aboveState)) {
                aboveState = level.getBlockState(pos.set(x, ++aboveY, z));
            }
            if (aboveY > topY + 1 && !aboveState.isAir())
                break;

            // The ash cells that end up ash again: their neighbours must not react to the
            // two updates skipped (see above)
            final int lastY = groundY + column.size();
            pendingTicks.clear();
            boolean quiet = true;
            for (int cellY = groundY + 1; cellY <= lastY && quiet; cellY++) {
                if (level.getBlockState(pos.set(x, cellY, z)).is(ashBlock)) {
                    quiet = quietNeighbours(level, pos, x, cellY, z, pendingTicks);
                }
            }
            if (!quiet)
                break;

            // Entities a falling block collides with (boats, minecarts, shulkers)
            final AABB path = new AABB(x + 0.01, groundY + 1, z + 0.01, x + 0.99, topY + 0.98, z + 0.99);
            if (!level.getEntities((Entity) null, path, Entity::canBeCollidedWith).isEmpty())
                break;

            // Landed one by one, each after its ground hit, then the cells left empty
            BlockState below = ground;
            for (int i = 0; i < column.size(); i++) {
                final BlockState landed = column.get(i);
                pos.set(x, groundY + 1 + i, z);
                level.gameEvent(GameEvent.HIT_GROUND, Vec3.atBottomCenterOf(pos), GameEvent.Context.of(null, below));
                if (level.getBlockState(pos) != landed) {
                    level.setBlock(pos, landed, 3);
                }
                below = landed;
            }
            for (int emptyY = groundY + 1 + column.size(); emptyY <= topY; emptyY++) {
                pos.set(x, emptyY, z);
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            // The ticks the skipped updates gave the falling blocks next to the column
            for (int i = 0; i < pendingTicks.size(); i++) {
                final BlockPos tickPos = pendingTicks.get(i);
                level.scheduleTick(tickPos, level.getBlockState(tickPos).getBlock(), 2);
            }
            next = above;
        }

        // The rest falls as before, in the order it fell
        for (int i = 0; i <= next; i++) {
            final BlockPos from = falls.get(i);
            FallingBlockEntity entity;
            try {
                entity = new FallingBlockEntity(level, from.getX() + 0.5, from.getY(), from.getZ() + 0.5, state);
            } catch (LinkageError e) {
                // Constructor not opened by the access transformer: the factory, whose block
                // change is a no-op here (the block is already the air it would set)
                FallingBlockEntity.fall(level, from, state);
                continue;
            }
            level.addFreshEntity(entity);
        }
    }

    /**
     * True if every block of the column from fromY up to below toY is air.
     */
    private static boolean onlyAir(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int fromY, int toY, int z) {
        for (int y = fromY; y < toY; y++) {
            if (!level.getBlockState(pos.set(x, y, z)).isAir())
                return false;
        }
        return true;
    }

    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    /**
     * True if none of the side neighbours of the cell reacts to it being emptied and
     * filled again (the cells above and below belong to the column). Plain falling blocks
     * would get a tick: added to ticks when it can make them fall.
     */
    private static boolean quietNeighbours(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z,
            List<BlockPos> ticks) {
        for (Direction dir : HORIZONTAL) {
            final int nx = x + dir.getStepX();
            final int nz = z + dir.getStepZ();
            // Never load a chunk to look at it
            final LevelChunk chunk = level.getChunkSource().getChunkNow(nx >> 4, nz >> 4);
            if (chunk == null)
                return false;
            final BlockState neighbour = chunk.getBlockState(pos.set(nx, y, nz));
            switch (neighbourKind(neighbour.getBlock())) {
                case NEIGHBOUR_QUIET:
                    break;
                case NEIGHBOUR_FALLING:
                    // The tick of a plain falling block only does something if it can fall
                    if (FallingBlock.isFree(chunk.getBlockState(pos.set(nx, y - 1, nz)))) {
                        ticks.add(new BlockPos(nx, y, nz));
                    }
                    break;
                case NEIGHBOUR_FALLING_OTHER_TICK:
                    ticks.add(new BlockPos(nx, y, nz));
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    private static final int NEIGHBOUR_QUIET = 0;
    private static final int NEIGHBOUR_FALLING = 1;
    private static final int NEIGHBOUR_FALLING_OTHER_TICK = 2;
    private static final int NEIGHBOUR_REACTS = 3;
    // Block class -> NEIGHBOUR_* (server thread)
    private static final Map<Class<?>, Integer> NEIGHBOUR_KIND = new HashMap<>();
    private static Method[] neighbourMethods;

    /**
     * How a block reacts to a neighbour changing: not at all (neither updateShape nor
     * neighborChanged overridden), as a plain falling block (FallingBlock's updateShape
     * and delay: a tick in 2, the tick is FallingBlock's or another one), or otherwise.
     * NEIGHBOUR_REACTS as well if this cannot be checked.
     */
    private static int neighbourKind(Block block) {
        return NEIGHBOUR_KIND.computeIfAbsent(block.getClass(), cls -> {
            try {
                if (neighbourMethods == null) {
                    neighbourMethods = new Method[] {
                            ObfuscationReflectionHelper.findMethod(BlockBehaviour.class, "m_7417_", BlockState.class,
                                    Direction.class, BlockState.class, LevelAccessor.class, BlockPos.class,
                                    BlockPos.class), // updateShape
                            // neighborChanged
                            ObfuscationReflectionHelper.findMethod(BlockBehaviour.class, "m_6861_", BlockState.class,
                                    Level.class, BlockPos.class, Block.class, BlockPos.class, boolean.class),
                            ObfuscationReflectionHelper.findMethod(FallingBlock.class, "m_7198_"), // getDelayAfterPlace
                            ObfuscationReflectionHelper.findMethod(BlockBehaviour.class, "m_213897_", BlockState.class,
                                    ServerLevel.class, BlockPos.class, RandomSource.class) // tick
                    };
                }
                final Class<?> updateShape = declaringClass(cls, neighbourMethods[0]);
                final Class<?> neighborChanged = declaringClass(cls, neighbourMethods[1]);
                if (neighborChanged != BlockBehaviour.class)
                    return NEIGHBOUR_REACTS;
                if (updateShape == BlockBehaviour.class)
                    return NEIGHBOUR_QUIET;
                if (updateShape == FallingBlock.class && declaringClass(cls, neighbourMethods[2]) == FallingBlock.class)
                    return declaringClass(cls, neighbourMethods[3]) == FallingBlock.class ? NEIGHBOUR_FALLING
                            : NEIGHBOUR_FALLING_OTHER_TICK;
                return NEIGHBOUR_REACTS;
            } catch (Throwable e) {
                return NEIGHBOUR_REACTS;
            }
        });
    }

    /**
     * The class that declares the method cls uses (cls itself or a superclass), null if none.
     */
    private static Class<?> declaringClass(Class<?> cls, Method method) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(method.getName(), method.getParameterTypes());
                return c;
            } catch (NoSuchMethodException e) {
                // look in the superclass
            }
        }
        return null;
    }

    // Block classes that override what happens to an entity landing on them (server thread)
    private static final Map<Class<?>, Boolean> REACTS_TO_LANDING = new HashMap<>();
    private static Method[] landingMethods;

    /**
     * True if the block does something to an entity landing on it (fallOn,
     * updateEntityAfterFallOn, stepOn overridden, e.g. slime blocks, redstone ore).
     * True as well if this cannot be checked.
     */
    private static boolean reactsToLanding(Block block) {
        return REACTS_TO_LANDING.computeIfAbsent(block.getClass(), cls -> {
            try {
                if (landingMethods == null) {
                    landingMethods = new Method[] {
                            ObfuscationReflectionHelper.findMethod(Block.class, "m_142072_", Level.class,
                                    BlockState.class, BlockPos.class, Entity.class, float.class), // fallOn
                            ObfuscationReflectionHelper.findMethod(Block.class, "m_5548_", BlockGetter.class,
                                    Entity.class), // updateEntityAfterFallOn
                            ObfuscationReflectionHelper.findMethod(Block.class, "m_141947_", Level.class,
                                    BlockPos.class, BlockState.class, Entity.class) // stepOn
                    };
                }
                for (Method method : landingMethods) {
                    if (cls.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass() != Block.class)
                        return true;
                }
                return false;
            } catch (Throwable e) {
                return true;
            }
        });
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
     * Snow and ice melt in the overworld from the start of the apocalypse on: none forms
     * any more. Called from world generation threads too.
     */
    public static boolean snowMelts(LevelReader level) {
        final Level world = level instanceof WorldGenLevel worldGen ? worldGen.getLevel()
                : level instanceof Level l ? l : null;
        return world != null && world.dimension() == Level.OVERWORLD && apocalypseStarted();
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
     * Play phase-specific music for apocalypse progression, to every player online
     */
    private static void playPhaseMusic(MinecraftServer server, ApocalypsePhases.Phase phase) {
        if (phase == lastPlayedPhase)
            return; // Avoid unnecessary repetition

        lastPlayedPhase = phase;

        // If apocalypse is over, restore normal music, else play the phase's music
        final SoundEvent sound = phase == ApocalypsePhases.Phase.PHASE_END ? SoundEvents.MUSIC_GAME.value()
                : phase.getMusicTrack().value();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Stop any currently playing music
            player.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));
            player.playNotifySound(sound, SoundSource.MUSIC, 1.0f, 1.0f);
        }
        if (phase == ApocalypsePhases.Phase.PHASE_END) {
            JudgementDayMod.LOGGER.info("[Apocalypse] Apocalypse ended — normal music restored.");
        } else {
            JudgementDayMod.LOGGER.info("[Apocalypse] Playing music for phase: " + phase.name());
        }
    }

    public static void resetState() {
        meltChecked.clear();
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

        // The day was moved forward by hand while the server was off (e.g. from day 25 to
        // day 200): the phases it skipped lower the heights now, one after the other as day
        // by day, so the chunks catch up with the heights of the day. Their titles are not
        // shown. Not after the ritual, which leaves the world as it was then.
        final int reachedPhase = Math.floorDiv(Math.min(day, ConfigManager.apocalypseMaxDays), daysPerPhase);
        if (ConfigManager.lastApocalypseStep == ConfigManager.UNSET && reachedPhase >= 1
                && ConfigManager.lastAppliedPhase < reachedPhase) {
            for (int phase = Math.max(ConfigManager.lastAppliedPhase + 1, 1); phase <= reachedPhase; phase++) {
                lowerHeights(phase);
            }
            ConfigManager.lastAppliedPhase = reachedPhase;
            ConfigManager.save();
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