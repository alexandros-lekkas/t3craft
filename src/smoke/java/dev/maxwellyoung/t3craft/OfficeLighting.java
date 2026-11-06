package dev.maxwellyoung.t3craft;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block light over an office template without a world: the same flood fill vanilla uses (one level per step,
 * plus the dampening of the block entered), fed by the real emission of every block state. It lets officeCheck
 * prove that every cell an agent can stand on is lit, day and night, before anyone loads the game.
 */
final class OfficeLighting {
	record Report(int cells, int min, double average, BlockPos darkest) {
		@Override public String toString() { return cells + " cells, min " + min + " at " + darkest.toShortString() + ", average " + String.format("%.1f", average); }
	}

	/** Template blocks with the lamps in their day or night state. */
	static Map<BlockPos, BlockState> blocks(int floors, boolean night) {
		var template = OfficeDesign.template(floors);
		Map<BlockPos, BlockState> out = new HashMap<>();
		for (var e : template.getAsJsonArray("blocks")) {
			var b = e.getAsJsonObject(); var p = b.getAsJsonArray("pos");
			out.put(new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()), parse(b.get("state").getAsString()));
		}
		for (var e : template.getAsJsonArray("lamps")) {
			var lamp = e.getAsJsonObject();
			var pos = new BlockPos(lamp.get("x").getAsInt(), lamp.get("y").getAsInt(), lamp.get("z").getAsInt());
			String state = lamp.get(night ? "night" : "day").getAsString();
			if (state.equals("air")) out.remove(pos); else out.put(pos, parse(state));
		}
		return out;
	}

	static BlockState parse(String state) {
		try { return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, new StringReader(state), false).blockState(); }
		catch (CommandSyntaxException e) { throw new IllegalArgumentException("Office template block: " + state, e); }
	}

	static Map<BlockPos, Integer> blockLight(Map<BlockPos, BlockState> blocks) {
		Map<BlockPos, Integer> light = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (var e : blocks.entrySet()) {
			int emission = e.getValue().getLightEmission();
			if (emission > 0) { light.put(e.getKey(), emission); queue.add(e.getKey()); }
		}
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll(); int level = light.get(pos);
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				BlockState state = blocks.get(next);
				int value = level - 1 - (state == null ? 0 : state.getLightDampening());
				if (value > light.getOrDefault(next, 0)) { light.put(next, value); queue.add(next); }
			}
		}
		return light;
	}

	private static boolean passable(BlockState state, boolean feet) {
		return state == null || state.is(Blocks.LIGHT) || feet && state.getBlock() instanceof CarpetBlock;
	}

	/** Feet positions of every interior cell a two-block-tall agent can stand in, stair treads included. */
	static List<BlockPos> walkable(Map<BlockPos, BlockState> blocks, int floors) {
		List<BlockPos> cells = new ArrayList<>();
		for (int floor = 0; floor < floors; floor++) {
			int base = floor * T3Office.FLOOR_HEIGHT;
			for (int x = 0; x < T3Office.WIDTH; x++) for (int z = 0; z < T3Office.LENGTH; z++) {
				for (int y = base; y < base + T3Office.HEIGHT; y++) {
					var pos = new BlockPos(x, y, z);
					BlockState below = blocks.get(pos.below());
					boolean standing = y == base ? below != null : below != null && below.getBlock() instanceof StairBlock;
					if (standing && passable(blocks.get(pos), true) && passable(blocks.get(pos.above()), false)) cells.add(pos);
				}
			}
		}
		return cells;
	}

	/** Open-air cells on the terrace, where the night sky gives no light of its own. */
	static List<BlockPos> terrace(Map<BlockPos, BlockState> blocks, int floors) {
		List<BlockPos> cells = new ArrayList<>();
		int y = floors * T3Office.FLOOR_HEIGHT;
		for (int x = 0; x < T3Office.WIDTH; x++) for (int z = 0; z < T3Office.LENGTH; z++) {
			var pos = new BlockPos(x, y, z);
			if (blocks.get(pos.below()) != null && passable(blocks.get(pos), true) && passable(blocks.get(pos.above()), false)) cells.add(pos);
		}
		return cells;
	}

	static Report report(Map<BlockPos, Integer> light, List<BlockPos> cells) {
		int min = Integer.MAX_VALUE; long total = 0; BlockPos darkest = null;
		for (BlockPos cell : cells) {
			int value = light.getOrDefault(cell, 0); total += value;
			if (value < min) { min = value; darkest = cell; }
		}
		return new Report(cells.size(), cells.isEmpty() ? 0 : min, cells.isEmpty() ? 0 : (double) total / cells.size(), darkest == null ? BlockPos.ZERO : darkest);
	}
}
