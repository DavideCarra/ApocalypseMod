package com.creatormc.judgementdaymod.utilities;

import java.util.ArrayList;
import java.util.List;

import com.creatormc.judgementdaymod.events.mixin.ChunkMapAccessor;
import com.creatormc.judgementdaymod.handlers.DayTracker;
import com.creatormc.judgementdaymod.models.ChunkToProcess;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;
import com.creatormc.judgementdaymod.setup.ModBlocks;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;

import io.netty.util.internal.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
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

            // Send title and subtitle
            player.connection.send(new ClientboundSetTitleTextPacket(currentPhase.getTitleComponent()));
            player.connection.send(new ClientboundSetSubtitleTextPacket(currentPhase.getDescriptionComponent()));
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));

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
                player.connection.send(new ClientboundSetTitleTextPacket(Phase.PHASE_END.getTitleComponent()));
                player.connection.send(new ClientboundSetSubtitleTextPacket(Phase.PHASE_END.getDescriptionComponent()));
                player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            }
        }
    }

    @SuppressWarnings("resource")
    public static void processChunk(ChunkToProcess chunkInfo) {
        try {
            final ServerLevel level = chunkInfo.getLevel();
            final int chunkX = chunkInfo.getChunkX();
            final int chunkZ = chunkInfo.getChunkZ();

            // Early returns for safety
            if (!level.getServer().isSameThread() || !level.hasChunk(chunkX, chunkZ)) {
                return;
            }

            final LevelChunk chunk = level.getChunk(chunkX, chunkZ);

            // Marker at the chunk's SW corner, below world
            int minY = level.getMinBuildHeight() + 1;
            BlockPos markerPos = new BlockPos(chunk.getPos().x * 16, minY, chunk.getPos().z * 16);

            // DURING apocalypse → gate processing behind the marker
            boolean hasMarker = level.getBlockState(markerPos).is(ModBlocks.APOCALYPSE_MARKER.get());

            // OUTSIDE apocalypse window → remove marker (if present) and bail out
            if (ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseEndDay
                    || ConfigManager.apocalypseCurrentDay < 0) {
                // Remove marker if present
                if (!hasMarker) {
                    return; // Do not place marker while this condition is true
                } else {
                    level.setBlock(markerPos, Blocks.AIR.defaultBlockState(), 3);
                }
            }

            // If the marker is NOT placed yet, place it now and process chunk
            if (!hasMarker) {
                level.setBlock(markerPos, ModBlocks.APOCALYPSE_MARKER.get().defaultBlockState(), 3);
            }

            // ===== LOGGING CRITICO =====
            final float percent = Phase.toPercent(ConfigManager.apocalypseCurrentDay, ConfigManager.apocalypseMaxDays);

            // OPTIMIZATION: Preallocate list with realistic capacity
            final List<BlockPos> changedPositions = new ArrayList<>(256);

            // PHASE 1: Water evaporation (if apocalypse is advanced enough)
            if (percent >= 40.0 || ConfigManager.apocalypseCurrentDay >= ConfigManager.apocalypseMaxDays) {
                evaporateWater(chunk, level, ConfigManager.minWaterEvaporationHeight, level.getMaxBuildHeight());
            }

            // PHASE 2: Surface transformation (ash, fire)
            transformSurface(chunk, level, percent, changedPositions);

            // PHASE 3: Update clients only if there were changes
            if (!changedPositions.isEmpty()) {
                updateClientsAndScheduleTicks(chunk, level, changedPositions);
            }

        } catch (Exception e) {
            System.err.println("[Apocalypse] Error processing chunk: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * WATER EVAPORATION
     * Removes ALL water using direct section access
     */
    private static void evaporateWater(LevelChunk chunk, ServerLevel level, int minBuildHeight, int maxBuildHeight) {
        final BlockState airState = Blocks.AIR.defaultBlockState();
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;

        // OPTIMIZATION: Preallocate list to avoid dynamic resizing
        final List<BlockPos> changedPositions = new ArrayList<>(256);

        // Process current chunk + all 8 neighbors (3x3 grid = 9 chunks total)
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                final int targetChunkX = chunkX + offsetX;
                final int targetChunkZ = chunkZ + offsetZ;

                if (!level.hasChunk(targetChunkX, targetChunkZ))
                    continue;

                final LevelChunk targetChunk = level.getChunk(targetChunkX, targetChunkZ);
                final LevelChunkSection[] sections = targetChunk.getSections();
                final int minSection = targetChunk.getMinSection();
                final int startX = targetChunkX << 4; // Multiply by 16
                final int startZ = targetChunkZ << 4;

                // Calculate section range to avoid useless loops
                final int minSecIndex = Math.max(0, (minBuildHeight >> 4) - minSection);
                final int maxSecIndex = Math.min(sections.length - 1, (maxBuildHeight >> 4) - minSection);

                // Track which sections were modified (for batch recalc)
                final boolean[] dirtySection = new boolean[sections.length];

                // Loop on sections, not individual Y blocks
                for (int secIndex = maxSecIndex; secIndex >= minSecIndex; secIndex--) {
                    final LevelChunkSection section = sections[secIndex];

                    // Skip empty sections
                    if (section == null || section.hasOnlyAir())
                        continue;

                    // Calculate section's world Y coordinate
                    final int sectionWorldY = (secIndex + minSection) << 4;

                    // Loop on local coordinates (0-15) instead of world coords
                    for (int ly = 15; ly >= 0; ly--) {
                        final int worldY = sectionWorldY + ly;

                        // Early exit if below evaporation limit
                        if (worldY <= ConfigManager.minWaterEvaporationHeight)
                            break;

                        if (worldY < minBuildHeight || worldY >= maxBuildHeight)
                            continue;

                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                // Direct section access
                                final BlockState state = section.getBlockState(lx, ly, lz);

                                if (Analyzer.isEvaporable(state)) {
                                    // Direct section modification
                                    section.setBlockState(lx, ly, lz, airState, false);
                                    dirtySection[secIndex] = true;

                                    // Save position for batch client update
                                    changedPositions.add(new BlockPos(startX + lx, worldY, startZ + lz));
                                }
                            }
                        }
                    }
                }

                // Batch recalc only modified sections (not every block)
                for (int i = 0; i < sections.length; i++) {
                    if (dirtySection[i] && sections[i] != null) {
                        sections[i].recalcBlockCounts();
                    }
                }

                // Mark chunk as unsaved only if it has changes
                if (!changedPositions.isEmpty()) {
                    targetChunk.setUnsaved(true);
                }
            }
        }
    }

    /**
     * SURFACE TRANSFORMATION
     * Converts surface blocks to ash, fire, or air based on apocalypse progress
     */
    private static void transformSurface(LevelChunk chunk, ServerLevel level, float percent,
            List<BlockPos> changedPositions) {
        final LevelChunkSection[] sections = chunk.getSections();
        final int startX = chunk.getPos().x << 4;
        final int startZ = chunk.getPos().z << 4;
        final int minSection = chunk.getMinSection();
        final int minBuildHeight = ConfigManager.minWaterEvaporationHeight;
        final int sectionsCount = sections.length;

        // Cache block states to avoid repeated calls
        final BlockState ashState = ModBlocks.ASH_BLOCK.get().defaultBlockState();
        final BlockState fireState = Blocks.FIRE.defaultBlockState();
        final BlockState airState = Blocks.AIR.defaultBlockState();

        // ThreadLocalRandom is faster for single-threaded operations
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final float apocalypseThreshold = percent / 100f;

        // Track which sections were modified
        final boolean[] dirtySection = new boolean[sectionsCount];

        // Track columns that need lighting updates
        final boolean[][] touchedColumn = new boolean[16][16];

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
                            } else if (Analyzer.isEvaporable(foundBlock)) {
                                newState = airState;
                            } else {
                                newState = ashState;
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
                    } else if (Analyzer.isEvaporable(currentBlock)) {
                        newState = airState;
                    } else {
                        newState = ashState;
                    }
                }

                // Apply transformation
                int finalSecIndex = (targetY >> 4) - minSection;
                if (finalSecIndex >= 0 && finalSecIndex < sectionsCount) {
                    LevelChunkSection targetSection = sections[finalSecIndex];
                    if (targetSection != null) {
                        int finalLy = targetY & 15;
                        targetSection.setBlockState(lx, finalLy, lz, newState, false);
                        dirtySection[finalSecIndex] = true;
                        changedPositions.add(new BlockPos(startX + lx, targetY, startZ + lz));
                        touchedColumn[lx][lz] = true;
                    }
                }
            }
        }

        // Batch update modified sections
        for (int i = 0; i < sectionsCount; i++) {
            if (dirtySection[i] && sections[i] != null) {
                sections[i].recalcBlockCounts();
            }
        }

        // Update heightmaps if there were changes
        if (!changedPositions.isEmpty()) {
            chunk.setUnsaved(true);
            Heightmap.primeHeightmaps(chunk, chunk.getStatus().heightmapsAfter());
        }
    }

    /**
     * * UPDATE CLIENTS AND SCHEDULE TICKS * Sends chunk updates to clients and
     * schedules block ticks for dynamic blocks
     */
    private static void updateClientsAndScheduleTicks(LevelChunk chunk, ServerLevel level,
            List<BlockPos> changedPositions) {
        final Block ashBlock = ModBlocks.ASH_BLOCK.get();
        final Block fireBlock = Blocks.FIRE;
        final BlockState airState = Blocks.AIR.defaultBlockState();
        final int tickDelay = 1;
        final boolean[][] touchedColumn = new boolean[16][16]; // Schedule ticks for dynamic blocks (ash, fire, falling
                                                               // blocks)
        for (BlockPos pos : changedPositions) {
            BlockState state = level.getBlockState(pos); // Schedule tick for ash blocks
            if (state.getBlock() == ashBlock) {
                // Schedule tick for fire blocks
                level.scheduleTick(pos, ashBlock, tickDelay);
            }
            if (state.getBlock() == fireBlock) {
                // Handle falling blocks (sand, gravel, etc.)
                level.scheduleTick(pos, fireBlock, tickDelay);
            } // Schedule tick for water
            if (Analyzer.isEvaporable(state)) {
                level.sendBlockUpdated(pos, state, airState, 3);
                level.scheduleTick(pos, Fluids.WATER, 1);
            }
            if (state.getBlock() instanceof FallingBlock) {
                BlockPos belowPos = pos.below();
                if (FallingBlock.isFree(level.getBlockState(belowPos))) {
                    FallingBlockEntity.fall(level, pos, state);
                }
            } // Mark column for lighting update
            int lx = Math.floorMod(pos.getX(), 16);
            int lz = Math.floorMod(pos.getZ(), 16);
            touchedColumn[lx][lz] = true;
        }

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
