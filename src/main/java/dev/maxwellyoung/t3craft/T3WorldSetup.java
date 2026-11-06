package dev.maxwellyoung.t3craft;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;

import java.util.List;
import java.util.Set;

/**
 * What happens the first time a T3 World starts: gamerules, the office near spawn, spawn on its
 * plaza, a sunny morning. Runs on the integrated server behind the loading screen, so the player
 * loads in facing a finished office. Ordinary worlds (no marker) are untouched.
 */
final class T3WorldSetup {
	static void onServerStarted(MinecraftServer server) {
		if (server.isDedicatedServer()) return;
		T3WorldData data;
		try {
			data = T3WorldData.get(server);
		} catch (RuntimeException e) {
			T3Log.LOGGER.warn("Could not read the T3 World marker", e);
			return;
		}
		if (data == null || data.setupDone) return;
		ServerLevel level = server.overworld();
		T3Log.LOGGER.info("Setting up T3 World '{}' (seed {}, {})", data.name, data.seed, data.creative ? "creative" : "survival");
		rules(server, data.creative);
		T3Seeds.Site site = site(level, T3Seeds.byId(data.seedId));
		if (site != null) {
			try {
				OfficePlacement.Placement placed = OfficePlacement.placeAt(level, site.origin(), site.facing(), data.floors);
				data.origin = placed.origin();
				data.rotation = placed.rotation();
				data.spawn = placed.spawn();
				data.spawnYaw = placed.spawnYaw();
				data.spawnPitch = placed.spawnPitch();
				server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, placed.spawn(), placed.spawnYaw(), placed.spawnPitch()));
				T3Log.LOGGER.info("Office placed at {} facing {} ({}); spawn {} yaw {} pitch {}", placed.origin().toShortString(), site.facing(), placed.rotation(),
					placed.spawn().toShortString(), placed.spawnYaw(), placed.spawnPitch());
			} catch (RuntimeException e) {
				T3Log.LOGGER.error("Office placement failed; the world keeps its vanilla spawn", e);
			}
		} else {
			T3Log.LOGGER.warn("No flat, dry spot for the office within {} blocks of spawn; /t3 office places it by hand", OfficePlacement.SEARCH_RADIUS);
		}
		CommandSourceStack quiet = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
		server.getCommands().performPrefixedCommand(quiet, "time set 1000");
		server.setWeatherParameters(6000, 0, false, false);
		data.setupDone = true;
		data.setDirty();
	}

	/**
	 * The curated site when it is still flat and dry, otherwise a search around it (worldgen can
	 * drift between versions); any other seed searches around the vanilla spawn.
	 */
	private static T3Seeds.Site site(ServerLevel level, T3Seeds.Seed seed) {
		if (seed == null || seed.site() == null) return OfficePlacement.findSite(level, level.getRespawnData().pos());
		if (OfficePlacement.suitable(level, seed.site())) return seed.site();
		T3Log.LOGGER.warn("Seed {}'s recorded office site {} is no longer flat and dry; searching nearby", seed.id(), seed.site().origin().toShortString());
		return OfficePlacement.findSite(level, seed.site().origin(), seed.site().facing());
	}

	/** Comfortable defaults for a world you code in. Cheats are already on from creation. */
	static void rules(MinecraftServer server, boolean creative) {
		GameRules rules = server.getGameRules();
		// Agents build through /fill and /clone; the vanilla cap (32768) stops a floor-sized fill.
		rules.set(GameRules.MAX_BLOCK_MODIFICATIONS, 1_000_000, server);
		rules.set(GameRules.KEEP_INVENTORY, true, server);
		rules.set(GameRules.RESPAWN_RADIUS, 0, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.MOB_GRIEFING, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.ADVANCE_TIME, true, server);
		if (creative) {
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.ADVANCE_WEATHER, false, server);
		}
	}

	/** The first arrival stands on the plaza looking at the door, whatever vanilla did with the spawn. */
	static void onJoin(ServerPlayer player, MinecraftServer server) {
		if (server.isDedicatedServer()) return;
		T3WorldData data = T3WorldData.get(server);
		if (data == null || !data.setupDone || data.arrived || !data.officePlaced()) return;
		BlockPos spawn = data.spawn;
		player.teleportTo(server.overworld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), data.spawnYaw, data.spawnPitch, true);
		data.arrived = true;
		data.setDirty();
	}
}
