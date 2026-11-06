package dev.maxwellyoung.t3craft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What makes a save a T3 World. The client writes this file into the new save before the integrated
 * server starts, the server reads it on its first start and fills in where the office went, and the
 * title screen scans saves for it. Vanilla SavedData of the overworld, so it lives at
 * {@code <save>/dimensions/minecraft/overworld/data/t3craft/world.dat} (26.3 keeps every dimension's data there).
 */
final class T3WorldData extends SavedData {
	static final Identifier ID = Identifier.fromNamespaceAndPath(T3CraftClient.MOD_ID, "world");

	static final Codec<T3WorldData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(d -> d.name),
		Codec.STRING.optionalFieldOf("seedId").forGetter(d -> Optional.ofNullable(d.seedId)),
		Codec.LONG.fieldOf("seed").forGetter(d -> d.seed),
		Codec.BOOL.fieldOf("creative").forGetter(d -> d.creative),
		Codec.STRING.listOf().optionalFieldOf("floors", List.of()).forGetter(d -> d.floors),
		Codec.BOOL.optionalFieldOf("setupDone", false).forGetter(d -> d.setupDone),
		BlockPos.CODEC.optionalFieldOf("origin").forGetter(d -> Optional.ofNullable(d.origin)),
		Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(d -> d.rotation),
		BlockPos.CODEC.optionalFieldOf("spawn").forGetter(d -> Optional.ofNullable(d.spawn)),
		Codec.FLOAT.optionalFieldOf("spawnYaw", 0f).forGetter(d -> d.spawnYaw),
		Codec.FLOAT.optionalFieldOf("spawnPitch", 0f).forGetter(d -> d.spawnPitch),
		Codec.BOOL.optionalFieldOf("arrived", false).forGetter(d -> d.arrived),
		Codec.BOOL.optionalFieldOf("welcomed", false).forGetter(d -> d.welcomed),
		Codec.LONG.optionalFieldOf("createdAt", 0L).forGetter(d -> d.createdAt),
		Codec.BOOL.optionalFieldOf("adopted").forGetter(d -> Optional.of(d.adopted))
	).apply(instance, T3WorldData::new));

	/**
	 * The datafix type decides which vanilla schema upgrades run over this file when the game
	 * version changes. Nothing in it is a vanilla structure (no block states, items or entities,
	 * just names, numbers and a BlockPos), which is exactly what command storage holds, so its
	 * type is the safe choice: the fixers leave the data alone.
	 */
	static final SavedDataType<T3WorldData> TYPE = new SavedDataType<>(ID, () -> new T3WorldData("T3 World", null, 0, true, List.of()),
		CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** World name as typed on the New T3 World screen. */
	String name;
	/** Curated seed id, or null for Surprise me / a custom seed. */
	String seedId;
	long seed;
	boolean creative;
	/** One office floor per label, bottom up (machine names at creation time). */
	List<String> floors;
	/** The first-start pass ran (office placed or skipped, spawn set, gamerules applied). */
	boolean setupDone;
	/** Office origin (inside floor corner of the ground floor) and how the building is turned; null if no site was found. */
	BlockPos origin;
	Rotation rotation = Rotation.NONE;
	/** Where you arrive: on the plaza in front of the door, looking at it. */
	BlockPos spawn;
	float spawnYaw;
	/** A little upward, so the canopy sign reads on arrival. */
	float spawnPitch;
	/** The first player was put on the plaza. */
	boolean arrived;
	/** The welcome toast was shown. */
	boolean welcomed;
	/**
	 * The client recorded this save's office as the world's office. Kept in the save, not inferred from the client
	 * config: a save deleted and created again under the same name has the same config key but a new office.
	 */
	boolean adopted;
	long createdAt;

	T3WorldData(String name, String seedId, long seed, boolean creative, List<String> floors) {
		this.name = name;
		this.seedId = seedId;
		this.seed = seed;
		this.creative = creative;
		this.floors = List.copyOf(floors);
		this.createdAt = System.currentTimeMillis();
	}

	private T3WorldData(String name, Optional<String> seedId, long seed, boolean creative, List<String> floors, boolean setupDone,
		Optional<BlockPos> origin, Rotation rotation, Optional<BlockPos> spawn, float spawnYaw, float spawnPitch, boolean arrived, boolean welcomed, long createdAt,
		Optional<Boolean> adopted) {
		this(name, seedId.orElse(null), seed, creative, floors);
		this.setupDone = setupDone;
		this.origin = origin.orElse(null);
		this.rotation = rotation;
		this.spawn = spawn.orElse(null);
		this.spawnYaw = spawnYaw;
		this.spawnPitch = spawnPitch;
		this.arrived = arrived;
		this.welcomed = welcomed;
		this.createdAt = createdAt;
		// Markers from before the flag: a world already entered once (welcomed) had its office adopted then.
		this.adopted = adopted.orElse(welcomed);
	}

	boolean officePlaced() {
		return origin != null && spawn != null;
	}

	/** The marker file inside a save folder, exactly where the overworld's SavedData storage looks for it. */
	static Path file(Path saveDir) {
		Path data = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(net.minecraft.world.level.Level.OVERWORLD, saveDir).resolve("data");
		return ID.withSuffix(".dat").resolveAgainst(data);
	}

	/** Writes the marker into a save folder that has no server running yet (world creation). */
	static void write(Path saveDir, T3WorldData data) throws IOException {
		Path file = file(saveDir);
		Files.createDirectories(file.getParent());
		CompoundTag tag = new CompoundTag();
		tag.put("data", CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow());
		NbtUtils.addCurrentDataVersion(tag);
		NbtIo.writeCompressed(tag, file);
	}

	/** Reads the marker without a server, for the title screen; null if the save is not a T3 World. */
	static T3WorldData read(Path saveDir) {
		Path file = file(saveDir);
		if (!Files.isRegularFile(file)) return null;
		try {
			CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			return tag.getCompound("data").flatMap(data -> CODEC.parse(NbtOps.INSTANCE, data).result()).orElse(null);
		} catch (IOException | RuntimeException e) {
			T3Log.LOGGER.warn("Unreadable T3 World marker {}", file, e);
			return null;
		}
	}

	/** This server's T3 World data, or null for an ordinary world. Server thread only. */
	static T3WorldData get(MinecraftServer server) {
		return server.overworld().getDataStorage().get(TYPE);
	}
}
