package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks an office template on foot, without a world: a player is 0.6 wide and 1.8 tall, steps up at most 0.6
 * without jumping, and climbs a full block only onto a bottom-half stair from its low side. officeCheck uses it
 * to prove every stair tread and landing can be stood on and every upper floor is reachable from the door.
 */
final class OfficeWalk {
	private static final double PLAYER = 1.8, STEP = 0.6, EPS = 1e-4;
	private static final Direction[] AROUND = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private final Map<BlockPos, BlockState> blocks;

	OfficeWalk(Map<BlockPos, BlockState> blocks) { this.blocks = blocks; }

	private VoxelShape shape(BlockPos pos) {
		BlockState state = blocks.get(pos);
		// The player opens doors on the way through.
		if (state == null || state.getBlock() instanceof DoorBlock) return null;
		VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos);
		return shape.isEmpty() ? null : shape;
	}

	/** Where feet rest in this cell (absolute Y), or NaN if the cell is solid or there is nothing to stand on. */
	double surface(BlockPos feet) {
		VoxelShape here = shape(feet);
		if (here != null) {
			double top = here.max(Direction.Axis.Y);
			return top <= STEP ? feet.getY() + top : Double.NaN; // a carpet or a bottom slab: stand on it in this cell
		}
		VoxelShape below = shape(feet.below());
		return below != null && below.max(Direction.Axis.Y) >= 1 - EPS ? feet.getY() : Double.NaN;
	}

	/** Nothing collides in this column between {@code from} and {@code to} (absolute Y). */
	private boolean clear(int x, int z, double from, double to) {
		for (int y = (int) Math.floor(from); y < Math.ceil(to); y++) {
			VoxelShape shape = shape(new BlockPos(x, y, z));
			if (shape == null) continue;
			double min = y + shape.min(Direction.Axis.Y), max = y + shape.max(Direction.Axis.Y);
			if (max > from + EPS && min < to - EPS) return false;
		}
		return true;
	}

	/** A cell a player can stand in: firm footing and 1.8 blocks of headroom above it. */
	boolean standable(BlockPos feet) {
		double s = surface(feet);
		return !Double.isNaN(s) && clear(feet.getX(), feet.getZ(), s, s + PLAYER);
	}

	/** One step on foot, no jumping: a small rise, any drop of up to one block, or onto a stair from its low side. */
	private BlockPos step(BlockPos from, double fromSurface, Direction direction) {
		BlockPos side = from.relative(direction);
		for (int dy : new int[] {0, 1, -1}) {
			BlockPos to = side.above(dy);
			if (!standable(to)) continue;
			double s = surface(to), rise = s - fromSurface;
			boolean ok = rise <= STEP + EPS && rise >= -1 - EPS;
			if (!ok && rise <= 1 + EPS) {
				// The tread under `to` is a bottom stair whose low half faces us: walk up it like a player does.
				BlockState tread = blocks.get(to.below());
				ok = tread != null && tread.getBlock() instanceof StairBlock && tread.getValue(StairBlock.HALF) == Half.BOTTOM
					&& tread.getValue(StairBlock.FACING) == direction;
			}
			// While straddling both cells the head is over the lower one too.
			if (ok && clear(from.getX(), from.getZ(), fromSurface, Math.max(s, fromSurface) + PLAYER)) return to;
		}
		return null;
	}

	/** Every cell reachable on foot from {@code start}, within the box. */
	Set<BlockPos> reach(BlockPos start, BlockPos min, BlockPos max) {
		Set<BlockPos> seen = new HashSet<>();
		if (!standable(start)) return seen;
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start); queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos at = queue.poll();
			double s = surface(at);
			for (Direction direction : AROUND) {
				BlockPos next = step(at, s, direction);
				if (next == null || next.getX() < min.getX() || next.getX() > max.getX() || next.getY() < min.getY() || next.getY() > max.getY()
					|| next.getZ() < min.getZ() || next.getZ() > max.getZ()) continue;
				if (seen.add(next)) queue.add(next);
			}
		}
		return seen;
	}

	/** Problems with the office's stairs for this floor count; empty when every floor can be walked to. */
	static List<String> problems(int floors) {
		JsonObject template = OfficeDesign.template(floors);
		var walk = new OfficeWalk(OfficeLighting.blocks(floors, false));
		List<String> problems = new ArrayList<>();
		JsonArray levels = template.getAsJsonObject("anchors").getAsJsonArray("floors");
		JsonObject ground = levels.get(0).getAsJsonObject(), door = ground.getAsJsonObject("door");
		BlockPos start = new BlockPos(door.get("x").getAsInt(), 0, door.get("z").getAsInt());
		var reached = walk.reach(start, new BlockPos(-1, 0, -1), new BlockPos(T3Office.WIDTH, OfficeDesign.top(floors), T3Office.LENGTH));
		if (reached.isEmpty()) problems.add("cannot stand inside the door at " + start.toShortString());
		for (int n = 0; n < floors; n++) {
			JsonObject level = levels.get(n).getAsJsonObject(), up = level.getAsJsonObject("stairUp");
			int base = n * T3Office.FLOOR_HEIGHT;
			int[] first = ints(up.getAsJsonArray("firstStep")), top = ints(up.getAsJsonArray("top"));
			String where = floors + " floors, floor " + (n + 1) + " stair";
			// Every tread, both columns: the feet cell above it has footing and headroom.
			for (int k = 0; k <= top[1] - first[1]; k++)
				for (int x = first[0]; x <= first[0] + 1; x++) {
					BlockPos feet = new BlockPos(x, base + first[1] + k + 1, first[2] + k);
					if (!walk.standable(feet)) problems.add(where + ": tread " + k + " not standable at " + feet.toShortString());
				}
			// The landing from the top tread to where agents arrive on the floor above.
			boolean roof = n == floors - 1;
			int arrivalZ = roof ? template.getAsJsonObject("anchors").getAsJsonObject("roof").getAsJsonObject("arrival").get("z").getAsInt()
				: levels.get(n + 1).getAsJsonObject().getAsJsonObject("arrival").get("z").getAsInt();
			int landingY = base + T3Office.FLOOR_HEIGHT;
			for (int z = top[2] + 1; z <= arrivalZ; z++)
				for (int x = first[0]; x <= first[0] + 1; x++) {
					BlockPos feet = new BlockPos(x, landingY, z);
					if (!walk.standable(feet)) problems.add(where + ": landing not standable at " + feet.toShortString());
				}
			BlockPos arrival = new BlockPos(first[0], landingY, arrivalZ);
			if (!reached.contains(arrival)) problems.add(where + ": " + (roof ? "roof" : "floor " + (n + 2)) + " arrival " + arrival.toShortString() + " not reachable on foot from the door");
		}
		return problems;
	}

	private static int[] ints(JsonArray a) {
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
		return out;
	}
}
