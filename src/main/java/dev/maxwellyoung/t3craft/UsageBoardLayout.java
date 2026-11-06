package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Where the usage limits board hangs: the {@code usage_board} anchor of the ground floor, one map per
 * cell of a {@code columns} x {@code rows} wall face, turned with the office. Pure, so every rotation
 * is checked without a world.
 */
final class UsageBoardLayout {
	static final JsonObject ANCHOR = OfficeDesign.floor().getAsJsonObject("usage_board");
	static final int COLUMNS = ANCHOR.get("columns").getAsInt(), ROWS = ANCHOR.get("rows").getAsInt();
	/** The design facing of the frames: the direction from the wall into the room. */
	static final Direction FACING = Direction.byName(ANCHOR.get("facing").getAsString());

	private UsageBoardLayout() {}

	/** One map of the board: its tile of the rendered image, the frame's cell and the way it faces, both in the world. */
	record Tile(int column, int row, BlockPos pos, Direction facing) {
		/** The wall block the frame hangs on. */
		BlockPos wall() { return pos.relative(facing.getOpposite()); }
	}

	/** Every tile, top-left first, in reading order as seen from the room. */
	static List<Tile> tiles(OfficeFrame frame) {
		var top = ANCHOR.getAsJsonArray("topLeft");
		int x = top.get(0).getAsInt(), y = top.get(1).getAsInt(), z = top.get(2).getAsInt();
		// Looking at the wall, right is a quarter turn counter-clockwise from the way the frames face.
		Direction right = FACING.getCounterClockWise();
		Direction facing = frame.direction(FACING);
		List<Tile> out = new ArrayList<>(COLUMNS * ROWS);
		for (int row = 0; row < ROWS; row++) for (int column = 0; column < COLUMNS; column++) {
			out.add(new Tile(column, row, frame.block(x + right.getStepX() * column, y - row, z + right.getStepZ() * column), facing));
		}
		return out;
	}

	/** The middle of the board in the world, for the "near enough to poll" check. */
	static net.minecraft.world.phys.Vec3 centre(OfficeFrame frame) {
		var tiles = tiles(frame);
		var a = tiles.getFirst().pos();
		var b = tiles.getLast().pos();
		return new net.minecraft.world.phys.Vec3((a.getX() + b.getX()) / 2.0 + .5, (a.getY() + b.getY()) / 2.0 + .5, (a.getZ() + b.getZ()) / 2.0 + .5);
	}

	/** One 128 x 128 map's colours out of the board's {@code 768 x 384} map-palette image. */
	static byte[] tileColours(byte[] board, int column, int row) {
		byte[] out = new byte[128 * 128];
		for (int y = 0; y < 128; y++) System.arraycopy(board, (row * 128 + y) * UsageBoardRenderer.WIDTH + column * 128, out, y * 128, 128);
		return out;
	}
}
