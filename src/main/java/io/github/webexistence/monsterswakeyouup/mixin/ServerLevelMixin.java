package io.github.webexistence.monsterswakeyouup.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.LevelChunk;
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
	ServerLevel serverLevel = (ServerLevel) (Object) this;

	@Unique
    private boolean spawnedMob;


	private WeightedRandomList<MobSpawnSettings.SpawnerData> getMobCandidateList(BlockPos mobSpawnBlock) {
		MobSpawnSettings mobSpawnBiomeSettings = serverLevel.getBiome(mobSpawnBlock).value().getMobSettings();
		WeightedRandomList<MobSpawnSettings.SpawnerData> weightedRandomList = mobSpawnBiomeSettings.getMobs(MobCategory.MONSTER);

		//weightedRandomList.unwrap().removeIf(spawnerData -> spawnerData.type != EntityType.CREEPER);

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
	private boolean performSleepSpawning() {
		RandomSource randomSource = serverLevel.getRandom();
		List<ServerPlayer> serverPlayers = serverLevel.getServer().getPlayerList().getPlayers();
		for (ServerPlayer player : serverPlayers) {
			//System.out.println("Player name: " + player.getName().getString());
			//System.out.println("    gameMode: " + player.gameMode.getGameModeForPlayer().getName());
			//System.out.println("    isSleepingLongEnough: " + player.isSleepingLongEnough());

			if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
				return false;
			}

			int playerPosX = (int) Math.floor(player.position().x);
			int playerPosY = (int) Math.floor(player.position().y);
			int playerPosZ = (int) Math.floor(player.position().z);
			//LevelChunk playerChunk = serverLevel.getChunk(playerPosX, playerPosZ);

			int numSpawnAttempts = 25;
			//int numSpawnAttempts = 1000;
			int lastAttempts = numSpawnAttempts - (numSpawnAttempts / 5);
			// Chooses a random location between 2 and 31 blocks away from player (circular); y is +/- 15 blocks
			// the last 5 attempts use a reduced range of xz +/- 7 and y +/- 3
			for (int i = 0; i < numSpawnAttempts; i++) {

				int minSpawnDistance = 2;
				int maxSpawnDistance = 32;
				int maxSpawnDistanceVertical = 15;

				int spawnX;
				int spawnZ;
				int squaredDistance;

				do {
					if (i < lastAttempts) {
						spawnX = randomSource.nextInt(maxSpawnDistance) - randomSource.nextInt(maxSpawnDistance);
						spawnZ = randomSource.nextInt(maxSpawnDistance) - randomSource.nextInt(maxSpawnDistance);
					} else {
						spawnX = randomSource.nextInt(maxSpawnDistance / 2 - 1) - randomSource.nextInt(maxSpawnDistance / 4 - 1);
						spawnZ = randomSource.nextInt(maxSpawnDistance / 2 - 1) - randomSource.nextInt(maxSpawnDistance / 4 - 1);
					}
					squaredDistance = (spawnX * spawnX) + (spawnZ * spawnZ);
				} while (squaredDistance <= minSpawnDistance
						|| squaredDistance >= (maxSpawnDistance * maxSpawnDistance));

				int range;
				if (i < lastAttempts) {
					range = maxSpawnDistance / 2;
				} else {
					range = maxSpawnDistance / 4;
				}
				int mobPosX = playerPosX + spawnX;
				int potentialMobPosY = playerPosY + randomSource.nextInt(range) - randomSource.nextInt(range);
				int mobPosY = Math.max(1, Math.min(256, potentialMobPosY));
				int mobPosZ = playerPosZ + spawnZ;

				//int minY = Math.max(Math.max(0, playerPosY - range), mobPosY - range);

				//System.out.println("#### SPAWN MOB!!!!! ####");
				//System.out.println("mobPosX, mobPosZ: ");
				//System.out.println(mobPosX + ", " + mobPosZ);
				//System.out.println("  mobPosY: " + mobPosY);

				//int chunkSize= 15;
				//do {
				//	if (serverLevel.)
				//}
				//Zombie mob = new Zombie(serverLevel);
				//mob.lookAt(player, 0, 0);

				BlockPos mobSpawnBlockPos = new BlockPos(mobPosX, mobPosY, mobPosZ);
				WeightedRandomList<MobSpawnSettings.SpawnerData> weightedRandomList = getMobCandidateList(mobSpawnBlockPos);

				// DEBUGGING
				//for (MobSpawnSettings.SpawnerData spawnerData : weightedRandomList.unwrap()) {
				//	System.out.println(spawnerData.type);
				//}

				Optional<MobSpawnSettings.SpawnerData> optional = weightedRandomList.getRandom(randomSource);
				if (optional.isEmpty()) {
					return false;
				}
				MobSpawnSettings.SpawnerData spawnerData = optional.get();
				// based on NaturalSpawner
				if (spawnerData.type.canSummon() && SpawnPlacements.isSpawnPositionOk(spawnerData.type, serverLevel, mobSpawnBlockPos)) {
					Entity entity;
					try {
						entity = spawnerData.type.create(serverLevel.getLevel());
					} catch (Exception exception) {
						//LOGGER.warn("Failed to create mob", exception);
						continue;
					}

					if (entity == null) {
						continue;
					}

					entity.moveTo(
							mobSpawnBlockPos.getX(),
							mobSpawnBlockPos.getY(),
							mobSpawnBlockPos.getZ(),
							randomSource.nextFloat() * 360.0F, 0.0F);
					if (entity instanceof Mob mob
							&& mob.checkSpawnRules(serverLevel, MobSpawnType.TRIGGERED)
							&& mob.checkSpawnObstruction(serverLevel)) {
						SpawnGroupData spawnGroupData = null;
						spawnGroupData = mob.finalizeSpawn(
								serverLevel, serverLevel.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.CHUNK_GENERATION, spawnGroupData
						);
						serverLevel.addFreshEntityWithPassengers(mob);
						System.out.println("SPAWNING MOB!!!!!");
						return true;
					}
				}

			}
		}
		return false;
	}

	private boolean monsterSpawningAllowed() {
		return this.serverLevel.getDifficulty() != Difficulty.PEACEFUL
				&& this.serverLevel.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
	}

	/* TODO: Insert an entirely new if() conditional that checks if hostile mobs
	    are allowed (check difficulty?), then runs logic to spawn monsters. */
	@Inject(
			method = "tick(Ljava/util/function/BooleanSupplier;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/players/SleepStatus;areEnoughDeepSleeping(ILjava/util/List;)Z",
					shift = At.Shift.AFTER
			)
	)
	private void checkMonsterSpawning(BooleanSupplier booleanSupplier, CallbackInfo ci) {
		//if (((ServerLevel) (Object) this).getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
	    if (monsterSpawningAllowed()) {
			//System.out.println("insert new conditional");
			this.spawnedMob = performSleepSpawning();
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
		return original && !this.spawnedMob;
	}

	// Targets ServerLevel line 345.
	@ModifyExpressionValue(
			method = "tick(Ljava/util/function/BooleanSupplier;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z",
					ordinal = 1
					//target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"
			)
	)
	private boolean allowWakeUpAllPlayers(boolean original) {
		return original && !this.spawnedMob;
	}

}