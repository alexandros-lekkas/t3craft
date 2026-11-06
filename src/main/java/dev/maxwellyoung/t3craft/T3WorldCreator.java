package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FileUtil;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Creates and reopens T3 Worlds. All the world-creation API drift stays in this one file. */
final class T3WorldCreator {
	static final String DEFAULT_NAME = "T3 World";

	/** A T3 World on disk, for the title screen. */
	record Existing(String levelId, T3WorldData data, long lastPlayed) {}

	/**
	 * Makes the world and starts it. The marker goes into the save folder first, so the integrated
	 * server finds it on its first start and builds the office before you load in.
	 */
	static void create(Minecraft minecraft, Screen parent, String name, T3Seeds.Seed seed, String customSeed, boolean creative, List<String> floors) throws IOException {
		String worldName = name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
		LevelStorageSource source = minecraft.getLevelSource();
		String levelId = FileUtil.findAvailableName(source.getBaseDir(), worldName, "");
		long worldSeed = seed != null ? seed.seed()
			: customSeed != null && !customSeed.isBlank() ? WorldOptions.parseSeed(customSeed).orElse(WorldOptions.randomSeed())
			: WorldOptions.randomSeed();
		T3WorldData.write(source.getLevelPath(levelId), new T3WorldData(worldName, seed == null ? null : seed.id(), worldSeed, creative, floors));
		LevelSettings settings = new LevelSettings(worldName, creative ? GameType.CREATIVE : GameType.SURVIVAL,
			new LevelSettings.DifficultySettings(creative ? Difficulty.PEACEFUL : Difficulty.NORMAL, false, false), true, WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(worldSeed, true, false);
		minecraft.createWorldOpenFlows().createFreshLevel(levelId, settings, options, WorldPresets::createNormalWorldDimensions, parent);
	}

	static void open(Minecraft minecraft, String levelId, Screen onCancel) {
		minecraft.createWorldOpenFlows().openWorld(levelId, () -> minecraft.gui.setScreen(onCancel));
	}

	/** Every save with a T3 World marker, most recently played first. */
	static List<Existing> existing(Minecraft minecraft) {
		Path saves = minecraft.getLevelSource().getBaseDir();
		List<Existing> found = new ArrayList<>();
		if (!Files.isDirectory(saves)) return found;
		try (Stream<Path> dirs = Files.list(saves)) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				// Only real saves: a marker left behind by a creation that never started is not a world to continue.
				Path level = dir.resolve("level.dat");
				if (!Files.isRegularFile(level)) continue;
				T3WorldData data = T3WorldData.read(dir);
				if (data == null) continue;
				long played = data.createdAt;
				try {
					played = Math.max(played, Files.getLastModifiedTime(level).toMillis());
				} catch (IOException ignored) {}
				found.add(new Existing(dir.getFileName().toString(), data, played));
			}
		} catch (IOException e) {
			T3Log.LOGGER.warn("Could not list saves", e);
		}
		found.sort(Comparator.comparingLong(Existing::lastPlayed).reversed());
		return found;
	}
}
