package com.creatormc.judgementdaymod.utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.creatormc.judgementdaymod.ModBlocks;
import com.creatormc.judgementdaymod.models.ChunkToProcess;

import io.netty.util.internal.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Field;

public class Generator {

    public static void handleDayEvent(int day, ServerPlayer player) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;

        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null)
            return;

        if (day == 1) {

        }

        if (day % 10 == 0) {
            if (ConfigManager.apocalypseStage < 100) {
                ConfigManager.apocalypseStage += 10;
                System.out.println("apocalypse status: " + ConfigManager.apocalypseStage);
                resetPlayerChunks(player);
            } else {
                if (!ApocalypseManager.isApocalypseActive()) {
                    ApocalypseManager.startApocalypse();
                }
                System.out.println("apocalypse status: " + ConfigManager.apocalypseStage);
                resetPlayerChunks(player); // todo rimuovere
            }
        }
    }

    // posso sopprimere il warning, la risorsa verrà chiusa da minecraft quando
    // necessario
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
            final int minBuildHeight = level.getMinBuildHeight();

            // Cache delle blockstate più usate
            final BlockState ashState = ModBlocks.ASH_BLOCK.get().defaultBlockState();
            final BlockState fireState = Blocks.FIRE.defaultBlockState();
            final BlockState airState = Blocks.AIR.defaultBlockState();

            // Ottimizzazione: ThreadLocalRandom più veloce per single-thread
            final ThreadLocalRandom random = ThreadLocalRandom.current();
            final float apocalypseThreshold = ConfigManager.apocalypseStage * 0.01f;
            final boolean isApocalypseActive = ApocalypseManager.isApocalypseActive();

            // Ottimizzazione: Pre-alloca con capacità realistica (max 256 blocchi per
            // chunk)
            final List<BlockPos> changedPositions = new ArrayList<>(256);
            final boolean[] dirtySection = new boolean[sections.length];

            // Cache per heightmap - accesso diretto più veloce
            final Heightmap wsHM = lc.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);

            // Ottimizzazione: Pre-calcola gli indici delle sezioni per evitare divisioni
            // ripetute
            final int sectionsCount = sections.length;

            // prima viene rimossa l'acqua, si itera da -1 per risolvere un problema sul
            if (isApocalypseActive) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int worldX = startX + lx;
                        int worldZ = startZ + lz;

                        // Coordinate locali nel chunk corrente
                        int actualX = worldX & 15;
                        int actualZ = worldZ & 15;

                        for (int removeY = level.getMaxBuildHeight() - 1; removeY >= -10; removeY--) {
                            int targetSecIndex = (removeY >> 4) - minSection;
                            if (targetSecIndex < 0 || targetSecIndex >= sections.length)
                                continue;

                            LevelChunkSection targetSection = sections[targetSecIndex];
                            if (targetSection == null)
                                continue;

                            int targetLy = removeY & 15;
                            BlockState targetState = targetSection.getBlockState(actualX, targetLy, actualZ);

                            if (Analyzer.isEvaporable(targetState)) {
                                targetSection.setBlockState(actualX, targetLy, actualZ, airState, false);
                                dirtySection[targetSecIndex] = true;
                                changedPositions.add(new BlockPos(worldX, removeY, worldZ));
                            }
                        }
                    }
                }
            }

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {

                    // Ottimizzazione: confronto diretto più veloce
                    if (random.nextFloat() > apocalypseThreshold)
                        continue;

                    // Cache del valore heightmap per evitare ricalcoli
                    int topY = wsHM.getFirstAvailable(lx, lz) - 1;
                    if (topY < minBuildHeight)
                        continue;

                    // Ottimizzazione: pre-calcola indici
                    int secIndex = (topY >> 4) - minSection;
                    if (secIndex < 0 || secIndex >= sectionsCount)
                        continue;

                    LevelChunkSection section = sections[secIndex];
                    if (section == null || section.hasOnlyAir())
                        continue;

                    int ly = topY & 15;
                    BlockState currentBlock = section.getBlockState(lx, ly, lz);
                    if (currentBlock.isAir())
                        continue;

                    // Determina trasformazione con logica ottimizzata
                    BlockState newState = ashState;
                    int targetY = topY;

                    // Ottimizzazione: cache del check per ash block
                    final Block ashBlock = ModBlocks.ASH_BLOCK.get();

                    if (currentBlock.is(ashBlock)) {
                        // Trova primo blocco non-cenere scendendo
                        for (int y = topY - 1; y > minBuildHeight; y--) {
                            final int checkSecIndex = (y >> 4) - minSection;
                            if (checkSecIndex < 0 || checkSecIndex >= sectionsCount)
                                break;

                            final LevelChunkSection checkSection = sections[checkSecIndex];
                            if (checkSection == null || checkSection.hasOnlyAir())
                                continue;

                            final int checkLy = y & 15;
                            BlockState foundBlock = checkSection.getBlockState(lx, checkLy, lz);

                            if (!foundBlock.is(ashBlock)) {
                                targetY = y;
                                // Determina il tipo di trasformazione inline
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
                            continue; // Nessun blocco trovato
                    } else {
                        // Blocco non-ash: determina trasformazione diretta
                        if (Analyzer.canBurn(currentBlock, null, null)) {
                            newState = fireState;
                        } else if (Analyzer.isEvaporable(currentBlock)) {
                            newState = airState;
                        } else {
                            newState = ashState;
                        }
                    }

                    // Piazza il blocco con check ottimizzato
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

            // Early return se nessuna modifica
            if (changedPositions.isEmpty())
                return;

            // Batch update delle sezioni
            for (int i = 0; i < sectionsCount; i++) {
                if (dirtySection[i] && sections[i] != null) {
                    sections[i].recalcBlockCounts();
                }
            }

            lc.setUnsaved(true);
            Heightmap.primeHeightmaps(lc, lc.getStatus().heightmapsAfter());

            // Ottimizzazione: usa batch update dove possibile
            final Block ashBlockRef = ModBlocks.ASH_BLOCK.get();
            final Block fireBlockRef = Blocks.FIRE;
            final int tickDelay = 40;

            // schedule ticks e falling blocks e correzione luci
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
            }

            // Refresh intero chunk intero per aggiornamento lato client
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(lc,
                    level.getLightEngine(), null, null);

            // manda a tutti i player che hanno in vista questo chunk
            for (ServerPlayer viewer : level.players()) {
                if (level.getChunkSource().chunkMap.getPlayers(lc.getPos(), false).contains(viewer)) {
                    viewer.connection.send(packet);
                }
            }

        } catch (Exception e) {
            System.err.println("Errore processChunk: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // il metodo rende tutti i chunk "unwatched" settando la render distance del
    // giocatore a 0 e poi resettandola
    // serve per poter ricaricare tutti i chunk quando scatta una nuova fase
    // dell'apocalisse
    public static void resetPlayerChunks(ServerPlayer player) {
        ServerLevel level = player.serverLevel();

        // verifica che ci sia effettivamente un player online
        if (player == null || player.hasDisconnected())
            return;

        // Approccio: triggerare manualmente ChunkWatchEvent.Watch per tutti i chunk
        // visibili
        ChunkPos playerPos = new ChunkPos(player.blockPosition());
        int viewDistance = level.getServer().getPlayerList().getViewDistance();

        // Itera attraverso tutti i chunk nel raggio di vista
        for (int x = -viewDistance; x <= viewDistance; x++) {
            for (int z = -viewDistance; z <= viewDistance; z++) {
                ChunkPos chunkPos = new ChunkPos(playerPos.x + x, playerPos.z + z);

                // Ottieni il chunk se è caricato
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
                if (chunk != null) {
                    // Crea e posta manualmente l'evento ChunkWatchEvent.Watch
                    ChunkWatchEvent.Watch watchEvent = new ChunkWatchEvent.Watch(player, chunk, level);
                    MinecraftForge.EVENT_BUS.post(watchEvent);
                }
            }
        }
    }

}
