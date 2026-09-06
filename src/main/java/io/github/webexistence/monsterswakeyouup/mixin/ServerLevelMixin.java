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
    private WeightedRandomList<MobSpawnSettings.SpawnerData> getMobCandidateList(BlockPos mobSpawnBlock) {
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
        return weightedRandomList;
    }

    /* Source for help/inspiration -- TheMasterCaver from the Minecraft Forums:
     *   https://www.minecraftforum.net/forums/minecraft-java-edition/suggestions/3163484-wake-up-surprise-mechanic-for-sleeping-in-unsafe?comment=18
     */
    @Unique
    private boolean performSleepSpawning() {
        System.out.println("performSleepSpawning() invoked let's goooooo!!");
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
            // Chooses a random location between 2 and 31 blocks away from player (circular); y is +/- 15 blocks
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

                int range;
                if (i < lastAttempts) {
                    range = maxSpawnDistance / 2;
                } else {
                    range = maxSpawnDistance / 4;
                }
                int mobPosX = playerPosX + spawnX;
                int mobPosZ = playerPosZ + spawnZ;

                // set up initial BlockPos for potential spawn
                BlockPos mobSpawnBlockPos = new BlockPos(mobPosX, playerPosY, mobPosZ);

                //System.out.println("INITIAL: " + mobSpawnBlockPos);

                WeightedRandomList<MobSpawnSettings.SpawnerData> weightedRandomList = getMobCandidateList(mobSpawnBlockPos);

                Optional<MobSpawnSettings.SpawnerData> optional = weightedRandomList.getRandom(serverLevel.random);
                if (optional.isEmpty()) {
                    continue;
                }
                MobSpawnSettings.SpawnerData spawnerData = optional.get();
                MobSpawnType mobSpawnType = MobSpawnType.NATURAL;

                // scan for valid Y coordinate; change BlockPos if one is found
                int maxSpawnY = playerPosY + maxSpawnDistanceVertical;
                int minSpawnY = playerPosY - maxSpawnDistanceVertical;
                boolean foundValidPosY = false;
                for (int y = maxSpawnY; y > minSpawnY; y--) {
                    mobSpawnBlockPos =  new BlockPos(mobPosX, y, mobPosZ);
                    if (SpawnPlacements.isSpawnPositionOk(spawnerData.type, serverLevel, mobSpawnBlockPos)
                            && SpawnPlacements.checkSpawnRules(EntityType.ZOMBIE, serverLevel, mobSpawnType, mobSpawnBlockPos, serverLevel.random)) {
                        foundValidPosY = true;
                        break;
                    }
                }
                //System.out.println("NEW: " + mobSpawnBlockPos);
                if (!foundValidPosY) {
                    continue;
                }
                System.out.println("Attempting to spawn mob...");

                // based on NaturalSpawner
                if (spawnerData.type.canSummon()) {
                    Entity entity;
                    try {
                        entity = spawnerData.type.create(serverLevel, null, mobSpawnBlockPos, mobSpawnType, false, false);
                    } catch (Exception exception) {
                        //LOGGER.warn("Failed to create mob", exception);
                        continue;
                    }

                    if (entity == null) {
                        continue;
                    }

                    Mob mob = (Mob) entity;
                    System.out.println(mob.toString());
                    mob.setSilent(true); // TODO: is this necessary?
                    mob.setOnGround(true); // necessary for createPath() to return non-null
                    Path path = mob.getNavigation().createPath(player.blockPosition(), 1, maxSpawnDistance);

                    if (path == null || !path.canReach()) {
                        System.out.println("path: Mob could not reach player " + player.getName().getString()+ ". Cancelling spawn.");
                        continue;
                    }
                    System.out.println(path.toString());

                    // Spawn the mob
                    SpawnGroupData spawnGroupData = null;
                    spawnGroupData = mob.finalizeSpawn(
                            serverLevel, serverLevel.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.CHUNK_GENERATION, spawnGroupData
                    );
                    mob.moveTo(player.position());
                    mob.setSilent(false);
                    serverLevel.addFreshEntityWithPassengers(mob);
                    System.out.println("SPAWNING MOB!!!!!");
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
        //if (((ServerLevel) (Object) this).getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
        if (monsterSpawningAllowed()) {
            //System.out.println("insert new conditional");
            // TODO: fix the fact that this invokes every tick despite the boolean checks
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
                    //target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"
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