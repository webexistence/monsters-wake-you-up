package io.github.webexistence.monsterswakeyouup.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.webexistence.monsterswakeyouup.MonstersWakeYouUp;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Unique
    private static final int MIN_SPAWN_DISTANCE = 2;
    @Unique
    private static final int MAX_SPAWN_DISTANCE = 32;
    @Unique
    private static final int MAX_SPAWN_DISTANCE_VERTICAL = 15;

    @Unique
    private static final Logger LOGGER = MonstersWakeYouUp.LOGGER;

    @Unique
    private static boolean spawnedMob;

    @Unique
    private ServerLevel getServerLevel() {
        return (ServerLevel) (Object) this;
    }

    @Unique
    @Nullable
    private MobSpawnSettings.SpawnerData getRandomMobSpawnerData(BlockPos mobSpawnBlock) {
        MobSpawnSettings mobSpawnBiomeSettings = getServerLevel().getBiome(mobSpawnBlock).value().getMobSettings();
        WeightedList<MobSpawnSettings.SpawnerData> weightedRandomList = mobSpawnBiomeSettings.getMobs(MobCategory.MONSTER);

        List<Weighted<MobSpawnSettings.SpawnerData>> filteredList = weightedRandomList
                .unwrap()
                .stream()
                .filter(
                        weightedSpawnerData -> {
                            MobSpawnSettings.SpawnerData spawnerData = weightedSpawnerData.value();
                            return spawnerData.type() != EntityTypes.CREEPER
                                    && spawnerData.type() != EntityTypes.ENDERMAN
                                    && spawnerData.type() != EntityTypes.SLIME;
                        }
                ).toList();
        weightedRandomList = WeightedList.of(filteredList);

        Optional<MobSpawnSettings.SpawnerData> optional = weightedRandomList.getRandom(getServerLevel().getRandom());
        if (optional.isEmpty()) {
            return null;
        }
        MobSpawnSettings.SpawnerData spawnerData = optional.get();
        return spawnerData;
    }

    /* Source for help/inspiration -- TheMasterCaver from the Minecraft Forums:
     *   https://www.minecraftforum.net/forums/minecraft-java-edition/suggestions/3163484-wake-up-surprise-mechanic-for-sleeping-in-unsafe?comment=18
     */
    @Unique
    private boolean performSleepSpawning() {
        LOGGER.debug("performSleepSpawning() invoked.");
        List<ServerPlayer> serverPlayers = getServerLevel().getServer().getPlayerList().getPlayers();
        for (ServerPlayer player : serverPlayers) {

            if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
                return false;
            }

            int playerPosX = (int) Math.floor(player.position().x);
            int playerPosY = (int) Math.floor(player.position().y);
            int playerPosZ = (int) Math.floor(player.position().z);

            int numSpawnAttempts = 25;
            int lastAttempts = 5;
            // Chooses a random location between min and max distance (config) blocks away from player (circular).
            // lastAttempts: num attempts at the end which use a reduced range for spawn attempts
            for (int i = 0; i < numSpawnAttempts; i++) {

                int spawnX;
                int spawnZ;
                int squaredDistance;

                do {
                    if (i < numSpawnAttempts - lastAttempts) {
                        // regular attempts
                        spawnX = getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE) - getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE);
                        spawnZ = getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE) - getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE);
                    } else {
                        // lastAttempts use reduced range
                        spawnX = getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE / 2 - 1) - getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE / 4 - 1);
                        spawnZ = getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE / 2 - 1) - getServerLevel().getRandom().nextInt(MAX_SPAWN_DISTANCE / 4 - 1);
                    }
                    squaredDistance = (spawnX * spawnX) + (spawnZ * spawnZ);
                    // squaredDistance is Pythagorean theorem: x^2 + z^2 = distance^2
                } while (squaredDistance <= MIN_SPAWN_DISTANCE
                        || squaredDistance >= (MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE));

                int mobPosX = playerPosX + spawnX;
                int mobPosZ = playerPosZ + spawnZ;

                // set up initial BlockPos for potential spawn
                BlockPos mobSpawnBlockPos = new BlockPos(mobPosX, playerPosY, mobPosZ);

                LOGGER.debug("Initial mobSpawnBlockPos: " + mobSpawnBlockPos);

                MobSpawnSettings.SpawnerData spawnerData = getRandomMobSpawnerData(mobSpawnBlockPos);
                if (spawnerData == null) {
                    continue;
                }
                EntitySpawnReason mobSpawnType = EntitySpawnReason.NATURAL;

                // scan for valid Y coordinate; change BlockPos if one is found
                int maxSpawnY = playerPosY + MAX_SPAWN_DISTANCE_VERTICAL;
                int minSpawnY = playerPosY - MAX_SPAWN_DISTANCE_VERTICAL;
                boolean foundValidPosY = false;
                for (int y = maxSpawnY; y > minSpawnY; y--) {
                    mobSpawnBlockPos =  new BlockPos(mobPosX, y, mobPosZ);
                    // TODO: Drowned seem to always fail the checkSpawnRules() check. Would like to be fixed.
                    if (SpawnPlacements.isSpawnPositionOk(spawnerData.type(), getServerLevel(), mobSpawnBlockPos)
                            && SpawnPlacements.checkSpawnRules(spawnerData.type(), getServerLevel(), mobSpawnType, mobSpawnBlockPos, getServerLevel().getRandom())) {
                        foundValidPosY = true;
                        break;
                    }
                }
                LOGGER.debug("Final mobSpawnBlockPos: " + mobSpawnBlockPos);
                if (!foundValidPosY) {
                    continue;
                }
                LOGGER.debug("Attempting to spawn mob...");

                // based on NaturalSpawner
                if (spawnerData.type().canSummon()) {
                    // Create entity object, but do not spawn it yet
                    Entity entity;
                    entity = spawnerData.type().create(getServerLevel(), mobSpawnType);
                    if (entity == null) {
                        continue;
                    }
                    entity.snapTo(Vec3.atCenterOf(mobSpawnBlockPos));

                    // Create mob object to do pathfinding check
                    Mob mob = (Mob) entity;
                    mob.setOnGround(true); // necessary for createPath() to return non-null
                    Path path = mob.getNavigation().createPath(player.blockPosition(), 1, MAX_SPAWN_DISTANCE);

                    if (path == null || !path.canReach()) {
                        LOGGER.debug("Mob ({}) could not pathfind to player {}. Cancelling spawn.", mob.getName().getString(), player.getName().getString());
                        continue;
                    }
                    LOGGER.debug("Path generated: {}", path);

                    // Finally, attempt to actually spawn the mob
                    SpawnGroupData spawnGroupData = null;
                    spawnGroupData = mob.finalizeSpawn(
                            getServerLevel(), getServerLevel().getCurrentDifficultyAt(mob.blockPosition()), mobSpawnType, spawnGroupData
                    );
                    mob.snapTo(player.position());
                    getServerLevel().addFreshEntityWithPassengers(mob);
                    LOGGER.info("Spawning mob ({}) on player {}.", mob.getName().getString(), player.getName().getString());
                    player.stopSleeping();
                    player.sendOverlayMessage(Component.translatable("block.minecraft.bed.not_safe"));
                    mob.setTarget(player); // enables the mob to immediately strike
                    return true;
                }

            }
        }
        return false;
    }

    @Unique
    private boolean monsterSpawningAllowed() {
        return getServerLevel().getDifficulty() != Difficulty.PEACEFUL
                && getServerLevel().getGameRules().get(GameRules.SPAWN_MOBS)
                && !spawnedMob;
    }

    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/dimension/DimensionType;defaultClock()Ljava/util/Optional;"
            )
    )
    private void checkMonsterSpawning(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        if (monsterSpawningAllowed()) {
            spawnedMob = performSleepSpawning();
        }
    }

    // Targets ServerLevel line ~372, the conditional that skips the night.
    @ModifyExpressionValue(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "java/util/Optional.isPresent()Z",
                    ordinal = 0
            )
    )
    private boolean allowSkipNight(boolean original) {
        return original && !spawnedMob;
    }

    // Targets ServerLevel line ~376, cancelling the method that wakes up all players.
    @Inject(
            method = "Lnet/minecraft/server/level/ServerLevel;wakeUpAllPlayers()V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void preventWakeUpAllPlayers(CallbackInfo ci) {
        if (spawnedMob) {
            ci.cancel();
        }
    }

    // Targets ServerLevel line ~377, the conditional that resets the weather.
    @ModifyExpressionValue(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z",
                    ordinal = 0
            )
    )
    private boolean allowResetWeather(boolean original) {
        return original && !spawnedMob;
    }


    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At("HEAD")
    )
    private void resetFlag(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        spawnedMob = false;
    }
}