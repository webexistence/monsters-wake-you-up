package io.github.webexistence.monsterswakeyouup.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.Nullable;
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
    private static final int minSpawnDistance = 2;
    @Unique
    private static final int maxSpawnDistance = 32;
    @Unique
    private static final int maxSpawnDistanceVertical = 15;

    @Unique
    ServerLevel serverLevel = (ServerLevel) (Object) this;

    @Unique
    private static boolean spawnedMob;

    @Unique
    @Nullable
    private MobSpawnSettings.SpawnerData getRandomMobSpawnerData(BlockPos mobSpawnBlock) {
        MobSpawnSettings mobSpawnBiomeSettings = serverLevel.getBiome(mobSpawnBlock).value().getMobSettings();
        WeightedRandomList<MobSpawnSettings.SpawnerData> weightedRandomList = mobSpawnBiomeSettings.getMobs(MobCategory.MONSTER);

        List<MobSpawnSettings.SpawnerData> filteredList = weightedRandomList
                .unwrap()
                .stream()
                .filter(
                        spawnerData ->
                                spawnerData.type != EntityType.CREEPER
                                        && spawnerData.type != EntityType.ENDERMAN
                                        && spawnerData.type != EntityType.SLIME
                ).toList();
        weightedRandomList = WeightedRandomList.create(filteredList);

        Optional<MobSpawnSettings.SpawnerData> optional = weightedRandomList.getRandom(serverLevel.random);
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
        List<ServerPlayer> serverPlayers = serverLevel.getServer().getPlayerList().getPlayers();
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
                        spawnX = serverLevel.random.nextInt(maxSpawnDistance) - serverLevel.random.nextInt(maxSpawnDistance);
                        spawnZ = serverLevel.random.nextInt(maxSpawnDistance) - serverLevel.random.nextInt(maxSpawnDistance);
                    } else {
                        // lastAttempts use reduced range
                        spawnX = serverLevel.random.nextInt(maxSpawnDistance / 2 - 1) - serverLevel.random.nextInt(maxSpawnDistance / 4 - 1);
                        spawnZ = serverLevel.random.nextInt(maxSpawnDistance / 2 - 1) - serverLevel.random.nextInt(maxSpawnDistance / 4 - 1);
                    }
                    squaredDistance = (spawnX * spawnX) + (spawnZ * spawnZ);
                    // squaredDistance is Pythagorean theorem: x^2 + z^2 = distance^2
                } while (squaredDistance <= minSpawnDistance
                        || squaredDistance >= (maxSpawnDistance * maxSpawnDistance));

                int mobPosX = playerPosX + spawnX;
                int mobPosZ = playerPosZ + spawnZ;

                // set up initial BlockPos for potential spawn
                BlockPos mobSpawnBlockPos = new BlockPos(mobPosX, playerPosY, mobPosZ);

                MobSpawnSettings.SpawnerData spawnerData = getRandomMobSpawnerData(mobSpawnBlockPos);
                if (spawnerData == null) {
                    continue;
                }
                MobSpawnType mobSpawnType = MobSpawnType.NATURAL;

                // scan for valid Y coordinate; change BlockPos if one is found
                int maxSpawnY = playerPosY + maxSpawnDistanceVertical;
                int minSpawnY = playerPosY - maxSpawnDistanceVertical;
                boolean foundValidPosY = false;
                for (int y = maxSpawnY; y > minSpawnY; y--) {
                    mobSpawnBlockPos =  new BlockPos(mobPosX, y, mobPosZ);
                    // TODO: Drowned seem to always fail the checkSpawnRules() check. Would like to be fixed.
                    if (SpawnPlacements.isSpawnPositionOk(spawnerData.type, serverLevel, mobSpawnBlockPos)
                            && SpawnPlacements.checkSpawnRules(spawnerData.type, serverLevel, mobSpawnType, mobSpawnBlockPos, serverLevel.random)) {
                        foundValidPosY = true;
                        break;
                    }
                }
                if (!foundValidPosY) {
                    continue;
                }

                // based on NaturalSpawner
                if (spawnerData.type.canSummon()) {
                    // Create entity object, but do not spawn it yet
                    Entity entity;
                    entity = spawnerData.type.create(serverLevel);
                    if (entity == null) {
                        continue;
                    }
                    entity.moveTo(mobSpawnBlockPos.getCenter());

                    // Create mob object to do pathfinding check
                    Mob mob = (Mob) entity;
                    mob.setOnGround(true); // necessary for createPath() to return non-null
                    Path path = mob.getNavigation().createPath(player.blockPosition(), 1, maxSpawnDistance);

                    if (path == null || !path.canReach()) {
                        //System.out.println("path: Mob could not reach player " + player.getName().getString()+ ". Cancelling spawn.");
                        continue;
                    }

                    // Finally, attempt to actually spawn the mob
                    SpawnGroupData spawnGroupData = null;
                    spawnGroupData = mob.finalizeSpawn(
                            serverLevel, serverLevel.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.CHUNK_GENERATION, spawnGroupData
                    );
                    mob.moveTo(player.position());
                    serverLevel.addFreshEntityWithPassengers(mob);
                    //System.out.println("SPAWNING MOB!!!!!");
                    player.stopSleeping();
                    return true;
                }

            }
        }
        return false;
    }

    @Unique
    private boolean monsterSpawningAllowed() {
        return this.serverLevel.getDifficulty() != Difficulty.PEACEFUL
                && this.serverLevel.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)
                && !spawnedMob;
    }

    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"
            )
    )
    private void checkMonsterSpawning(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        if (monsterSpawningAllowed()) {
            spawnedMob = performSleepSpawning();
        }
    }

    // Targets ServerLevel line 340.
    @ModifyExpressionValue(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z",
                    ordinal = 0
            )
    )
    private boolean allowSkipNight(boolean original) {
        return original && !spawnedMob;
    }

    // Targets ServerLevel line 345.
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

    // Targets ServerLevel line 346.
    @ModifyExpressionValue(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z",
                    ordinal = 1
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