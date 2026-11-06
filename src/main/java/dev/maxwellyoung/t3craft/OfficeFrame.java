package dev.maxwellyoung.t3craft;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * Where an office stands and which way it is turned: the one mapping between the design's
 * coordinates (origin at the inside floor corner, door on the north side, view to the south) and
 * the world. Blocks, entity positions, yaws, hit tests and relative commands all go through here,
 * so a turned office is the same office with every piece in the right place.
 *
 * <p>Vanilla's structure transform does the turning: block cells and continuous positions rotate
 * about the origin cell the same way {@code StructureTemplate} places a rotated template, and
 * block states rotate with {@link BlockState#rotate}, which covers facing, axis and pane/wall
 * connections.
 */
record OfficeFrame(BlockPos origin, Rotation rotation) {
	static OfficeFrame at(BlockPos origin) {
		return new OfficeFrame(origin, Rotation.NONE);
	}

	/** Design cell to world block. */
	BlockPos block(int x, int y, int z) {
		return StructureTemplate.transform(new BlockPos(x, y, z), Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
	}

	/** Design position (cell centres, entity feet) to world position. */
	Vec3 point(double x, double y, double z) {
		Vec3 turned = StructureTemplate.transform(new Vec3(x, y, z), Mirror.NONE, rotation, BlockPos.ZERO);
		return turned.add(origin.getX(), origin.getY(), origin.getZ());
	}

	/** World block back to the design cell, for hit tests. */
	BlockPos local(BlockPos world) {
		return StructureTemplate.transform(world.subtract(origin), Mirror.NONE, inverse(), BlockPos.ZERO);
	}

	/** A design yaw turned with the office (NaN, "face the player", stays NaN). */
	float yaw(float local) {
		return Float.isNaN(local) ? local : Mth.wrapDegrees(local + degrees());
	}

	/** A block state turned with the office. */
	BlockState state(BlockState state) {
		return rotation == Rotation.NONE ? state : state.rotate(rotation);
	}

	int degrees() {
		return switch (rotation) {
			case NONE -> 0;
			case CLOCKWISE_90 -> 90;
			case CLOCKWISE_180 -> 180;
			case COUNTERCLOCKWISE_90 -> 270;
		};
	}

	Rotation inverse() {
		return switch (rotation) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> rotation;
		};
	}

	/**
	 * A relative {@code setblock x y z state} / {@code fill x1 y1 z1 x2 y2 z2 state [mode]} command
	 * made absolute: coordinates turned and moved to the world, the block state turned to match.
	 * Any NBT after the state (sign text) is kept as is.
	 */
	String command(String relative) {
		if (rotation == Rotation.NONE) return T3Office.absolute(relative, origin.getX(), origin.getY(), origin.getZ());
		int coords = relative.startsWith("fill") ? 6 : 3;
		String[] parts = relative.split(" ", coords + 2);
		for (int i = 0; i < coords; i += 3) {
			BlockPos world = block(Integer.parseInt(parts[i + 1]), Integer.parseInt(parts[i + 2]), Integer.parseInt(parts[i + 3]));
			parts[i + 1] = Integer.toString(world.getX());
			parts[i + 2] = Integer.toString(world.getY());
			parts[i + 3] = Integer.toString(world.getZ());
		}
		parts[coords + 1] = rotateState(parts[coords + 1]);
		return String.join(" ", parts);
	}

	/** Turns the state at the front of a command tail ("birch_wall_sign[facing=east]{...} hollow"). */
	private String rotateState(String tail) {
		int end = tail.length();
		int nbt = tail.indexOf('{');
		if (nbt >= 0) end = nbt;
		int space = tail.indexOf(' ');
		if (space >= 0 && space < end) end = space;
		String state = tail.substring(0, end);
		if (state.indexOf('[') < 0) return tail; // No properties, nothing to turn.
		try {
			BlockState parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, new StringReader(state), false).blockState();
			return BlockStateParser.serialize(state(parsed)) + tail.substring(end);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			throw new IllegalArgumentException("Unreadable block state in office command: " + state, e);
		}
	}
}
