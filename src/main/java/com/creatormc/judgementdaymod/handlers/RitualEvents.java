package com.creatormc.judgementdaymod.handlers;

import com.creatormc.judgementdaymod.setup.ModItems;
import com.creatormc.judgementdaymod.utilities.ApocalypsePhases.Phase;
import com.creatormc.judgementdaymod.utilities.ConfigManager;
import com.creatormc.judgementdaymod.setup.ModBlocks;
import com.creatormc.judgementdaymod.setup.JudgementDayMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

@Mod.EventBusSubscriber(modid = JudgementDayMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RitualEvents {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        if (level.isClientSide)
            return;

        if (event.getHand() != InteractionHand.MAIN_HAND)
            return;

        BlockState state = level.getBlockState(pos);
        ServerLevel serverLevel = (ServerLevel) level;

        // Player and item in main hand
        ItemStack held = event.getItemStack();
        var entity = event.getEntity();

        // -------------------------------------------------
        // SECOND RITUAL: right–click GRASS_BLOCK with Heart
        // -------------------------------------------------
        if (state.is(Blocks.GRASS_BLOCK) && held.is(ModItems.HEART_OF_OBLIVION.get())) {

            // Consume one Heart of Oblivion (unless in creative)
            if (entity instanceof ServerPlayer player && !player.getAbilities().instabuild) {
                held.shrink(1);
            }

            // Spawn a fake dropped heart entity at ritual position
            ItemEntity heartEntity = new ItemEntity(
                    serverLevel,
                    pos.getX() + 0.5,
                    pos.getY() + 1.0, // slightly above the grass
                    pos.getZ() + 0.5,
                    new ItemStack(ModItems.HEART_OF_OBLIVION.get()));
            serverLevel.addFreshEntity(heartEntity);

            System.out.println("SPAWN HEART ENTITY: " + heartEntity);

            // Call your epic ritual logic
            ServerPlayer player = entity instanceof ServerPlayer sp ? sp : null;
            performHeartOnGrassRitualS(serverLevel, heartEntity, player);

            return; // do not run the ash-block ritual
        }

        // ---------------------------------
        // FIRST RITUAL: Ash Block pattern
        // ---------------------------------
        if (!state.is(ModBlocks.ASH_BLOCK.get())) {
            return;
        }

        if (!isValidRitual(serverLevel, pos)) {
            return;
        }

        performHeartOfOblivionRitual(serverLevel, pos);
    }

    private static boolean isValidRitual(Level level, BlockPos center) {
        return checkPattern(level, center, "north") ||
                checkPattern(level, center, "south") ||
                checkPattern(level, center, "east") ||
                checkPattern(level, center, "west");
    }

    private static boolean checkPattern(Level level, BlockPos center, String direction) {
        BlockPos eggPos, reinforcedPos, debrisPos;

        switch (direction) {
            case "north":
                eggPos = center.north();
                reinforcedPos = center.south().west();
                debrisPos = center.south().east();
                break;
            case "south":
                eggPos = center.south();
                reinforcedPos = center.north().east();
                debrisPos = center.north().west();
                break;
            case "east":
                eggPos = center.east();
                reinforcedPos = center.west().north();
                debrisPos = center.west().south();
                break;
            case "west":
                eggPos = center.west();
                reinforcedPos = center.east().south();
                debrisPos = center.east().north();
                break;
            default:
                return false;
        }

        BlockState eggState = level.getBlockState(eggPos);
        BlockState reinforcedState = level.getBlockState(reinforcedPos);
        BlockState debrisState = level.getBlockState(debrisPos);

        return eggState.is(Blocks.DRAGON_EGG) &&
                reinforcedState.is(Blocks.REINFORCED_DEEPSLATE) &&
                debrisState.is(Blocks.ANCIENT_DEBRIS);
    }

    private static void performHeartOfOblivionRitual(ServerLevel level, BlockPos center) {
        // Find the valid orientation
        BlockPos eggPos = null;
        BlockPos reinforcedPos = null;
        BlockPos debrisPos = null;

        if (checkPattern(level, center, "north")) {
            eggPos = center.north();
            reinforcedPos = center.south().west();
            debrisPos = center.south().east();
        } else if (checkPattern(level, center, "south")) {
            eggPos = center.south();
            reinforcedPos = center.north().east();
            debrisPos = center.north().west();
        } else if (checkPattern(level, center, "east")) {
            eggPos = center.east();
            reinforcedPos = center.west().south();
            debrisPos = center.west().north();
        } else if (checkPattern(level, center, "west")) {
            eggPos = center.west();
            reinforcedPos = center.east().north();
            debrisPos = center.east().south();
        }

        if (eggPos == null || reinforcedPos == null || debrisPos == null) {
            return;
        }

        final BlockPos definedEggPos = eggPos;
        final BlockPos definedReinforcedPos = reinforcedPos;
        final BlockPos definedDebrisPos = debrisPos;

        Vec3 spawnPos = Vec3.atCenterOf(center.above());

        // PHASE 1: Initial ominous sound and particles (immediate)
        level.playSound(null, center, SoundEvents.WITHER_SPAWN, SoundSource.BLOCKS, 1.5f, 0.5f);
        level.playSound(null, center, SoundEvents.AMBIENT_CAVE.value(), SoundSource.BLOCKS, 1.0f, 0.8f);

        spawnInitialParticles(level, center, spawnPos);

        // PHASE 2: Building intensity (after 1 second)
        level.getServer().tell(new net.minecraft.server.TickTask(20, () -> {
            level.playSound(null, center, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 1.2f, 0.6f);
            spawnBuildupParticles(level, center, spawnPos);
        }));

        // PHASE 3: More intensity (after 2 seconds)
        level.getServer().tell(new net.minecraft.server.TickTask(40, () -> {
            level.playSound(null, center, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.BLOCKS, 1.0f, 0.5f);
            spawnIntenseParticles(level, center, spawnPos);
        }));

        // PHASE 4: Lightning strikes (after 2.5 seconds)
        level.getServer().tell(new net.minecraft.server.TickTask(50, () -> {
            // Strike lightning at each ritual block
            strikeLightning(level, center);
            strikeLightning(level, definedEggPos);
            strikeLightning(level, definedReinforcedPos);
            strikeLightning(level, definedDebrisPos);

            level.playSound(null, center, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 1.5f, 0.8f);
        }));

        // PHASE 5: Climax and destruction (after 3 seconds)
        BlockPos finalEgg = eggPos;
        BlockPos finalReinforced = reinforcedPos;
        BlockPos finalDebris = debrisPos;

        level.getServer().tell(new net.minecraft.server.TickTask(60, () -> {
            // Massive particle explosion
            spawnExplosionParticles(level, spawnPos);

            // Destroy blocks
            level.destroyBlock(center, false);
            level.destroyBlock(finalEgg, false);
            level.destroyBlock(finalReinforced, false);
            level.destroyBlock(finalDebris, false);

            // Epic sounds
            level.playSound(null, center, SoundEvents.ENDER_DRAGON_DEATH, SoundSource.BLOCKS, 2.0f, 0.7f);
            level.playSound(null, center, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.5f, 1.0f);
            level.playSound(null, center, SoundEvents.WITHER_DEATH, SoundSource.BLOCKS, 1.0f, 0.5f);

            // Visual effects
            level.levelEvent(2003, center, 0); // Dragon death particles
            level.levelEvent(1038, center, 0); // Ender chest open particles
        }));

        // PHASE 6: Spawn the Heart (after 3.5 seconds)
        level.getServer().tell(new net.minecraft.server.TickTask(70, () -> {
            // Final particle burst
            for (int i = 0; i < 100; i++) {
                double offsetX = (level.random.nextDouble() - 0.5) * 0.5;
                double offsetY = level.random.nextDouble() * 0.5;
                double offsetZ = (level.random.nextDouble() - 0.5) * 0.5;

                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        spawnPos.x + offsetX, spawnPos.y + offsetY, spawnPos.z + offsetZ,
                        1, 0, 0, 0, 0.05);
            }

            // Spawn the Heart of Oblivion
            ItemStack heartStack = new ItemStack(ModItems.HEART_OF_OBLIVION.get());
            ItemEntity drop = new ItemEntity(level, spawnPos.x, spawnPos.y, spawnPos.z, heartStack);
            drop.setDefaultPickUpDelay();
            drop.setGlowingTag(true); // Make it glow
            level.addFreshEntity(drop);

            // Final mystical sound
            level.playSound(null, center, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.0f, 1.5f);
        }));
    }

    private static void spawnInitialParticles(ServerLevel level, BlockPos center, Vec3 spawnPos) {
        // Dark ominous particles rising
        for (int i = 0; i < 30; i++) {
            double angle = (i / 30.0) * Math.PI * 2;
            double radius = 2.0;
            double x = spawnPos.x + Math.cos(angle) * radius;
            double z = spawnPos.z + Math.sin(angle) * radius;

            level.sendParticles(ParticleTypes.SMOKE,
                    x, center.getY(), z,
                    1, 0, 0.5, 0, 0.02);
        }
    }

    private static void spawnBuildupParticles(ServerLevel level, BlockPos center, Vec3 spawnPos) {
        // Purple portal particles spiraling upward
        for (int i = 0; i < 50; i++) {
            double angle = (i / 50.0) * Math.PI * 4;
            double radius = 2.0 - (i / 50.0) * 1.5;
            double height = (i / 50.0) * 3;
            double x = spawnPos.x + Math.cos(angle) * radius;
            double z = spawnPos.z + Math.sin(angle) * radius;

            level.sendParticles(ParticleTypes.PORTAL,
                    x, center.getY() + height, z,
                    2, 0.1, 0.1, 0.1, 0.5);

            level.sendParticles(ParticleTypes.WITCH,
                    x, center.getY() + height, z,
                    1, 0, 0, 0, 0);
        }
    }

    private static void spawnIntenseParticles(ServerLevel level, BlockPos center, Vec3 spawnPos) {
        // Soul particles converging to center
        for (int i = 0; i < 80; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double radius = 3.0;
            double x = spawnPos.x + Math.cos(angle) * radius;
            double z = spawnPos.z + Math.sin(angle) * radius;
            double y = center.getY() + level.random.nextDouble() * 4;

            level.sendParticles(ParticleTypes.SOUL,
                    x, y, z,
                    1, -Math.cos(angle) * 0.3, 0, -Math.sin(angle) * 0.3, 0.3);

            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    x, y, z,
                    3, 0.5, 0.5, 0.5, 0.1);
        }
    }

    private static void spawnExplosionParticles(ServerLevel level, Vec3 spawnPos) {
        // Massive particle explosion
        for (int i = 0; i < 200; i++) {
            double vx = (level.random.nextDouble() - 0.5) * 2;
            double vy = (level.random.nextDouble() - 0.5) * 2;
            double vz = (level.random.nextDouble() - 0.5) * 2;

            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    spawnPos.x, spawnPos.y, spawnPos.z,
                    1, vx, vy, vz, 0.5);

            level.sendParticles(ParticleTypes.END_ROD,
                    spawnPos.x, spawnPos.y, spawnPos.z,
                    1, vx, vy, vz, 0.3);
        }

        // Shockwave
        for (int i = 0; i < 60; i++) {
            double angle = (i / 60.0) * Math.PI * 2;
            double radius = 4.0;
            double x = spawnPos.x + Math.cos(angle) * radius;
            double z = spawnPos.z + Math.sin(angle) * radius;

            level.sendParticles(ParticleTypes.EXPLOSION,
                    x, spawnPos.y, z,
                    1, 0, 0, 0, 0);
        }
    }

    private static void strikeLightning(ServerLevel level, BlockPos pos) {
        LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(level);
        if (lightning != null) {
            lightning.moveTo(Vec3.atBottomCenterOf(pos));
            lightning.setVisualOnly(true); // No fire or damage
            level.addFreshEntity(lightning);
        }
    }

    private static void performHeartOnGrassRitualS(ServerLevel level, ItemEntity heart, ServerPlayer player) {
        final Vec3 center = heart.position();
        final BlockPos centerPos = heart.blockPosition();

        // Freeze the heart above the ground
        heart.setNoGravity(true);
        heart.setDeltaMovement(Vec3.ZERO);
        heart.noPhysics = true;
        heart.setInvulnerable(true);

        // Dramatic thunderstorm
        level.setWeatherParameters(0, 6000, true, true);

        // --- PHASE 1: dark sounds + smoke
        level.playSound(null, centerPos, SoundEvents.WITHER_SPAWN, SoundSource.AMBIENT, 1.5f, 0.5f);

        for (int i = 0; i < 50; i++) {
            double offX = (level.random.nextDouble() - 0.5) * 2.0;
            double offZ = (level.random.nextDouble() - 0.5) * 2.0;
            double offY = level.random.nextDouble() * 1.5;

            level.sendParticles(ParticleTypes.SMOKE,
                    center.x + offX, center.y + offY, center.z + offZ,
                    1, 0, 0, 0, 0.02);

            level.sendParticles(ParticleTypes.SOUL,
                    center.x + offX, center.y + offY, center.z + offZ,
                    1, 0, 0.02, 0, 0.02);
        }

        // --- PHASE 2: portal vortex (after 1s)
        level.getServer().tell(new TickTask(20, () -> {
            level.playSound(null, centerPos, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 1.2f, 0.7f);

            for (int i = 0; i < 100; i++) {
                double angle = (i / 100.0) * Math.PI * 4;
                double radius = 3.0;
                double height = (i / 100.0) * 3.0;

                double x = center.x + Math.cos(angle) * radius;
                double z = center.z + Math.sin(angle) * radius;
                double y = center.y + height * 0.05;

                level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                        x, y, z,
                        2, 0.1, 0.1, 0.1, 0.2);

                level.sendParticles(ParticleTypes.WITCH,
                        x, y, z,
                        1, 0, 0, 0, 0);
            }
        }));

        // --- PHASE 3: lightning circle (after 2s)
        level.getServer().tell(new TickTask(40, () -> {
            level.playSound(null, centerPos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2.0f, 0.9f);

            for (int i = 0; i < 8; i++) {
                double angle = (i / 8.0) * Math.PI * 2;
                double radius = 6.0;

                double x = center.x + Math.cos(angle) * radius;
                double z = center.z + Math.sin(angle) * radius;

                BlockPos strikePos = BlockPos.containing(x, center.y, z);

                var lightning = EntityType.LIGHTNING_BOLT.create(level);
                if (lightning != null) {
                    lightning.moveTo(strikePos.getX() + 0.5, strikePos.getY(), strikePos.getZ() + 0.5);
                    lightning.setVisualOnly(true);
                    level.addFreshEntity(lightning);
                }

                level.sendParticles(ParticleTypes.EXPLOSION,
                        strikePos.getX() + 0.5,
                        strikePos.getY() + 0.5,
                        strikePos.getZ() + 0.5,
                        3, 0.5, 0.5, 0.5, 0.1);
            }
        }));

        // --- PHASE 4: explosion + ritual success (after 3s)
        level.getServer().tell(new TickTask(60, () -> {

            level.playSound(null, centerPos, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.AMBIENT, 2.0f, 0.5f);
            level.playSound(null, centerPos, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.5f, 1.2f);

            // Particle burst
            for (int i = 0; i < 200; i++) {
                double vx = (level.random.nextDouble() - 0.5) * 2.0;
                double vy = (level.random.nextDouble() - 0.5) * 2.0;
                double vz = (level.random.nextDouble() - 0.5) * 2.0;

                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        center.x, center.y + 0.5, center.z,
                        1, vx, vy, vz, 0.3);

                level.sendParticles(ParticleTypes.END_ROD,
                        center.x, center.y + 0.5, center.z,
                        1, vx * 0.5, vy * 0.5, vz * 0.5, 0.1);
            }

            // Heart disappears
            heart.discard();

            if (player != null) {
                ConfigManager.apocalypseEndDay = ConfigManager.apocalypseCurrentDay;
                if (ConfigManager.apocalypseMaxDays >= ConfigManager.apocalypseCurrentDay) {
                    ConfigManager.apocalypseMaxDays = ConfigManager.apocalypseCurrentDay - 1;
                }
                PhaseTitleOverlay.displayPhaseTitle(Phase.PHASE_END.getTitleComponent(),
                        Phase.PHASE_END.getDescriptionComponent());
            }
            ConfigManager.save();
        }));
    }

}