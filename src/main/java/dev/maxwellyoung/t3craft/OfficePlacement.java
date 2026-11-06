package dev.maxwellyoung.t3craft;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the Scandi Studio ({@link OfficeDesign}) gets put into a world, on the server thread
 * and without chat or command packets. {@link #place} builds it in a given {@link OfficeFrame};
 * {@link #placeAt} is the T3 World entry point that also picks the turn from the view direction,
 * loads the chunks and grounds the plaza. {@link #findSite} finds a flat, dry spot for seeds
 * without a recorded one.
 *
 * <p>The design is authored with its door on the north side (-z) and its glazed view side facing
 * south (+z); {@code facing} is where that view side ends up.
 */
final class OfficePlacement {
	/** How far in front of the door you arrive: back enough that the canopy sign and the facade read. */
	static final int SPAWN_DISTANCE = 7;
	private static final int FOUNDATION_DEPTH = 12;
	/** Rings around spawn the site search covers, and how long it may take. */
	static final int SEARCH_RADIUS = 160;
	private static final long SEARCH_BUDGET_NANOS = 3_000_000_000L;

	/** The result: where everything went, in world coordinates. */
	record Placement(OfficeFrame frame, Direction facing, BlockPos door, BlockPos spawn, float spawnYaw, float spawnPitch) {
		BlockPos origin() { return frame.origin(); }
		Rotation rotation() { return frame.rotation(); }
	}

	/** Which way to turn the south-facing design so its view side faces {@code facing}. */
	static Rotation rotationFor(Direction facing) {
		return switch (facing) {
			case SOUTH -> Rotation.NONE;
			case WEST -> Rotation.CLOCKWISE_90;
			case NORTH -> Rotation.CLOCKWISE_180;
			case EAST -> Rotation.COUNTERCLOCKWISE_90;
			default -> throw new IllegalArgumentException("The office faces a horizontal direction, not " + facing);
		};
	}

	/** Lays out the office without touching a world: door and arrival spot for this origin and facing. */
	static Placement plan(BlockPos origin, Direction facing) {
		OfficeFrame frame = new OfficeFrame(origin, rotationFor(facing));
		int[] outside = OfficeDesign.doorOutside();
		int[] sign = OfficeDesign.exterior("sign");
		BlockPos door = frame.block(outside[0], 0, outside[1]);
		BlockPos spawn = frame.block(outside[0], 0, outside[1] - SPAWN_DISTANCE);
		// You stand on the plaza looking at the door, which is the design's south, turned.
		float yaw = frame.yaw(0);
		// Eyes on the canopy sign: a little up, so the facade and its name are in the frame.
		double eye = 1.62, rise = sign[1] + 0.5 - eye;
		double run = Math.hypot(sign[0] + 0.5 - (outside[0] + 0.5), sign[2] + 0.5 - (outside[1] - SPAWN_DISTANCE + 0.5));
		float pitch = (float) -Math.toDegrees(Math.atan2(rise, run));
		return new Placement(frame, facing, door, spawn, yaw, pitch);
	}

	static void place(ServerLevel level, BlockPos origin, List<String> labels) {
		place(level, OfficeFrame.at(origin), labels);
	}

	/** The building plus its floor and canopy signs, one floor pair per label. */
	static void place(ServerLevel level, OfficeFrame frame, List<String> labels) {
		int floors = Math.max(2, labels.size() * 2);
		place(level, frame, floors);
		for (int floor = 0; floor < floors; floor++)
			label(level, frame.block(29, floor * 6 + 2, 13), "FLOOR " + (floor + 1), labels.isEmpty() ? "T3 STUDIO" : labels.get(floor / 2));
		int[] sign = OfficeDesign.exterior("sign");
		label(level, frame.block(sign[0], sign[1], sign[2]), "T3 OFFICE", "SCANDI STUDIO");
	}

	private static void label(ServerLevel level, BlockPos pos, String first, String second) {
		if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
			var text = net.minecraft.world.level.block.entity.SignText.EMPTY.asMutable().setLine(0, net.minecraft.network.chat.Component.literal(first)).setLine(1, net.minecraft.network.chat.Component.literal(second)).asImmutable();
			sign.setText(text, net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
			sign.setChanged();
			level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
		}
	}

	static int place(ServerLevel level, BlockPos origin, int floors) {
		return place(level, OfficeFrame.at(origin), floors);
	}

	/** Places the building, turned with the frame, and returns how many dropped items it had to sweep up afterwards. */
	static int place(ServerLevel level, OfficeFrame frame, int floors) {
		var data = OfficeDesign.template(floors);
		int[] e = OfficeDesign.EXTENT;
		// Top-down, so carpets and signs are gone before the blocks under them; untouched air is skipped.
		for (int x = e[0]; x <= e[1]; x++)
			for (int z = e[2]; z <= e[3]; z++)
				for (int y = OfficeDesign.top(floors); y >= 0; y--) {
					var pos = frame.block(x, y, z);
					// Hidden light blocks count as air, so name them: an older lighting layout must not linger under the new one.
					var state = level.getBlockState(pos);
					if (!state.isAir() || state.is(Blocks.LIGHT)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
				}
		var registry = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		try {
			for (var element : data.getAsJsonArray("blocks")) {
				var b = element.getAsJsonObject();
				var p = b.getAsJsonArray("pos");
				var state = BlockStateParser.parseForBlock(registry, new StringReader(b.get("state").getAsString()), false).blockState();
				level.setBlock(frame.block(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()), frame.state(state), 2);
			}
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
			throw new IllegalStateException("Invalid office template", ex);
		}
		return sweep(level, frame, floors);
	}

	static int sweep(ServerLevel level, BlockPos origin, int floors) {
		return sweep(level, OfficeFrame.at(origin), floors);
	}

	/** Anything that still popped off during the rebuild must not litter the office. */
	static int sweep(ServerLevel level, OfficeFrame frame, int floors) {
		var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds(frame, floors));
		items.forEach(net.minecraft.world.entity.item.ItemEntity::discard);
		return items.size();
	}

	static AABB bounds(BlockPos origin, int floors) {
		return bounds(OfficeFrame.at(origin), floors);
	}

	/** The whole build with a one-block margin, in the world. */
	static AABB bounds(OfficeFrame frame, int floors) {
		int[] e = OfficeDesign.EXTENT;
		BlockPos a = frame.block(e[0] - 1, OfficeDesign.GROUND - 1, e[2] - 1), b = frame.block(e[1] + 1, OfficeDesign.top(floors) + 2, e[3] + 1);
		return new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
			Math.max(a.getX(), b.getX()) + 1, b.getY() + 1, Math.max(a.getZ(), b.getZ()) + 1);
	}

	/**
	 * Builds the office in a T3 World. Loads (generating if needed) every chunk it touches, fills the
	 * ground under the plaza so nothing floats, then places the design turned toward {@code facing}.
	 */
	static Placement placeAt(ServerLevel level, BlockPos origin, Direction facing, List<String> labels) {
		Placement placement = plan(origin, facing);
		int floors = Math.max(2, labels.size() * 2);
		AABB box = bounds(placement.frame(), floors);
		for (int cx = ((int) box.minX) >> 4; cx <= ((int) box.maxX) >> 4; cx++)
			for (int cz = ((int) box.minZ) >> 4; cz <= ((int) box.maxZ) >> 4; cz++) level.getChunk(cx, cz);
		foundation(level, placement.frame());
		place(level, placement.frame(), labels);
		lawn(level, placement.frame());
		BlockPos spawn = arrival(level, placement.frame());
		if (!spawn.equals(placement.spawn())) placement = new Placement(placement.frame(), facing, placement.door(), spawn, placement.spawnYaw(), placement.spawnPitch());
		return placement;
	}

	/**
	 * The arrival spot is a few blocks beyond the plaza edge, on natural ground: stand on it, not in it. A pond,
	 * a tree or a cliff there moves it toward the door, down to the plaza cell just outside it, which is always dry.
	 */
	private static BlockPos arrival(ServerLevel level, OfficeFrame frame) {
		int[] outside = OfficeDesign.doorOutside();
		for (int back = SPAWN_DISTANCE; back > 0; back--) {
			BlockPos column = frame.block(outside[0], 0, outside[1] - back);
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
			BlockPos feet = new BlockPos(column.getX(), ground, column.getZ());
			if (Math.abs(ground - frame.origin().getY()) <= MAX_ARRIVAL_STEP
				&& standable(level.getBlockState(feet.below()), level.getBlockState(feet), level.getBlockState(feet.above()))) return feet;
		}
		return frame.block(outside[0], 0, outside[1]);
	}

	/** How far above or below the office floor the arrival may stand before it counts as a cliff or a hole. */
	private static final int MAX_ARRIVAL_STEP = 4;

	/** Firm, dry ground with room to stand: not water, not the top of a trunk or a canopy. */
	static boolean standable(BlockState below, BlockState feet, BlockState head) {
		if (!below.getFluidState().isEmpty() || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
		if (tree(below) || below.isAir() || below.canBeReplaced()) return false;
		return (feet.isAir() || feet.canBeReplaced()) && (head.isAir() || head.canBeReplaced());
	}

	/** Trunks and canopies, by tag in a world and by kind or name where tags are not loaded (the headless checks). */
	private static boolean tree(BlockState state) {
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) return true;
		String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return key.endsWith("_log") || key.endsWith("_wood") || key.endsWith("_stem") || key.endsWith("_hyphae");
	}

	/**
	 * Grass on every open lawn cell. The clear strips what stood above the office floor, so where the ground rose
	 * above the plaza it leaves the dirt (or stone) under the old surface bare; give it its grass back.
	 */
	private static void lawn(ServerLevel level, OfficeFrame frame) {
		BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		java.util.Set<Long> paved = pavedColumns();
		int[] e = OfficeDesign.EXTENT;
		for (int x = e[0]; x <= e[1]; x++) {
			for (int z = e[2]; z <= e[3]; z++) {
				if (paved.contains(((long) x << 32) ^ (z & 0xffffffffL))) continue;
				BlockPos top = frame.block(x, OfficeDesign.GROUND, z);
				if (bareLawn(level.getBlockState(top), level.getBlockState(top.above()))) level.setBlock(top, grass, 2);
			}
		}
	}

	/** A lawn cell whose surface is exposed dirt or stone under open air. */
	static boolean bareLawn(BlockState surface, BlockState above) {
		if (!above.isAir()) return false;
		return surface.is(Blocks.DIRT) || surface.is(Blocks.COARSE_DIRT) || surface.is(Blocks.ROOTED_DIRT) || surface.is(Blocks.STONE) || surface.is(BlockTags.BASE_STONE_OVERWORLD);
	}

	private static java.util.Set<Long> pavedColumns() {
		java.util.Set<Long> paved = new java.util.HashSet<>();
		for (int[] column : OfficeDesign.plaza()) paved.add(((long) column[0] << 32) ^ (column[1] & 0xffffffffL));
		return paved;
	}

	/**
	 * Solid ground under the whole footprint: below every plaza and slab column, and up to ground
	 * level in the lawn gaps between them, air, water and plants are replaced down to firm ground
	 * (dirt, grass on top, at most 12 deep). Nothing floats and nothing opens onto a hole.
	 */
	private static void foundation(ServerLevel level, OfficeFrame frame) {
		BlockState dirt = Blocks.DIRT.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState();
		java.util.Set<Long> paved = pavedColumns();
		int[] e = OfficeDesign.EXTENT;
		for (int x = e[0]; x <= e[1]; x++) {
			for (int z = e[2]; z <= e[3]; z++) {
				boolean lawn = !paved.contains(((long) x << 32) ^ (z & 0xffffffffL));
				BlockPos top = frame.block(x, lawn ? OfficeDesign.GROUND : OfficeDesign.GROUND - 1, z);
				for (int depth = 0; depth < FOUNDATION_DEPTH; depth++) {
					BlockPos pos = top.below(depth);
					if (pos.getY() < level.getMinY()) break;
					if (!soft(level.getBlockState(pos))) break;
					level.setBlock(pos, lawn && depth == 0 ? grass : dirt, 2);
				}
			}
		}
	}

	private static boolean soft(BlockState state) {
		return state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
	}

	/** Surface heights straight from the generator's noise, cached per 4-block column so candidates share samples. */
	private static final class Terrain {
		private final ServerLevel level;
		private final ChunkGenerator generator;
		private final RandomState random;
		private final Map<Long, int[]> columns = new HashMap<>();

		Terrain(ServerLevel level) {
			this.level = level;
			this.generator = level.getChunkSource().getGenerator();
			this.random = level.getChunkSource().randomState();
		}

		/** {surface, floor} at the 4-block grid point nearest this column; surface above floor means water. */
		int[] at(int x, int z) {
			int gx = x & ~3, gz = z & ~3;
			return columns.computeIfAbsent(((long) gx << 32) ^ (gz & 0xffffffffL), key -> new int[] {
				generator.getBaseHeight(gx, gz, Heightmap.Types.WORLD_SURFACE_WG, level, random),
				generator.getBaseHeight(gx, gz, Heightmap.Types.OCEAN_FLOOR_WG, level, random)});
		}
	}

	/** How a candidate site scores: null when wet or too uneven, otherwise lower is better. */
	private record Score(double value, int originY, int range, double view) {}

	/** Per block from the search centre: a fine view is worth walking for, but not across the map. */
	private static final double DISTANCE_WEIGHT = 0.1;
	/** Turning away from the facing a place was chosen for costs this much. */
	private static final double FACING_PENALTY = 6;

	private static Score score(Terrain terrain, OfficeFrame frame, int distance) {
		return score(terrain, frame, distance, 0);
	}

	private static Score score(Terrain terrain, OfficeFrame frame, int distance, double penalty) {
		int[] e = OfficeDesign.EXTENT;
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		List<Integer> heights = new ArrayList<>();
		for (int x = e[0]; x <= e[1]; x += 4) {
			for (int z = e[2]; z <= e[3]; z += 4) {
				BlockPos p = frame.block(x, 0, z);
				int[] column = terrain.at(p.getX(), p.getZ());
				if (column[0] - column[1] > 0) return null;
				heights.add(column[0]);
				min = Math.min(min, column[0]);
				max = Math.max(max, column[0]);
				if (max - min > 4) return null;
			}
		}
		heights.sort(null);
		int originY = heights.get(heights.size() / 2);
		// View: open, downhill or water ahead of the glazing scores higher.
		double view = 0;
		int samples = 0;
		int centreX = (e[0] + e[1]) / 2;
		for (int out = 16; out <= 48; out += 16) {
			for (int side = -1; side <= 1; side++) {
				BlockPos p = frame.block(centreX + side * out / 2, 0, e[3] + out);
				int[] column = terrain.at(p.getX(), p.getZ());
				view += Math.clamp(originY - column[0], 0, 24) + (column[0] - column[1] > 0 ? 8 : 0);
				samples++;
			}
		}
		view /= samples;
		return new Score(3 * (max - min) + DISTANCE_WEIGHT * distance - view + penalty, originY, max - min, view);
	}

	/** Whether a recorded site still has flat, dry ground under the whole footprint (worldgen can drift between versions). */
	static boolean suitable(ServerLevel level, T3Seeds.Site site) {
		return score(new Terrain(level), new OfficeFrame(site.origin(), rotationFor(site.facing())), 0) != null;
	}

	/**
	 * For a seed without a recorded site: the flattest dry spot near {@code around} with the best
	 * view, read from the generator's noise heightmaps (no chunks generated). Null if nothing within
	 * {@link #SEARCH_RADIUS} blocks is flat enough. Capped at three seconds; the best so far wins.
	 */
	static T3Seeds.Site findSite(ServerLevel level, BlockPos around) {
		return findSite(level, around, null);
	}

	/** As above, preferring the facing the place was chosen for (null: any). */
	static T3Seeds.Site findSite(ServerLevel level, BlockPos around, Direction preferred) {
		Terrain terrain = new Terrain(level);
		long deadline = System.nanoTime() + SEARCH_BUDGET_NANOS;
		T3Seeds.Site best = null;
		double bestScore = Double.MAX_VALUE;
		Direction[] facings = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
		int[] e = OfficeDesign.EXTENT;
		for (int radius = 0; radius <= SEARCH_RADIUS; radius += 8) {
			int steps = radius == 0 ? 1 : Math.max(8, radius / 4);
			for (int step = 0; step < steps; step++) {
				double angle = 2 * Math.PI * step / steps;
				int cx = around.getX() + (int) Math.round(radius * Math.cos(angle));
				int cz = around.getZ() + (int) Math.round(radius * Math.sin(angle));
				for (Direction facing : facings) {
					Rotation rotation = rotationFor(facing);
					// The origin sits so the footprint is centred on the candidate point.
					BlockPos centre = new OfficeFrame(BlockPos.ZERO, rotation).block((e[0] + e[1]) / 2, 0, (e[2] + e[3]) / 2);
					OfficeFrame frame = new OfficeFrame(new BlockPos(cx - centre.getX(), 0, cz - centre.getZ()), rotation);
					Score score = score(terrain, frame, radius, preferred == null || preferred == facing ? 0 : FACING_PENALTY);
					if (score == null) continue;
					if (score.value() < bestScore) {
						bestScore = score.value();
						best = new T3Seeds.Site(new BlockPos(frame.origin().getX(), score.originY(), frame.origin().getZ()), facing);
					}
					if (radius <= 32 && score.range() <= 1 && score.view() > 6 && (preferred == null || preferred == facing)) return best; // Flat, close and with a view: good enough.
					if (System.nanoTime() > deadline) return best;
				}
			}
		}
		return best;
	}
}
