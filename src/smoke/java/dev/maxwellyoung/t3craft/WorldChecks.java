package dev.maxwellyoung.t3craft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The T3 World pieces that don't need a running game: the seed list, the marker, and office placement math for every turn. */
public final class WorldChecks {
	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		checkSeeds();
		checkMarker();
		checkPlacement();
		checkFrame();
		System.out.println("WorldChecks passed");
	}

	private static void checkSeeds() {
		List<T3Seeds.Seed> seeds = T3Seeds.all();
		check(seeds.size() == 3, "three curated places, got " + seeds.size());
		T3Seeds.Seed first = T3Seeds.defaultSeed();
		check(first.id().equals("lakeside") && first.seed() == -2085440121970745551L, "Lakeside is the default with the owner's seed");
		check(T3Seeds.byId("cherry-plains").seed() == -4474845086078965121L, "Cherry Plains seed");
		check(T3Seeds.byId("river-bend").seed() == 3478701220631481329L, "River Bend seed");
		check(first.site().origin().equals(new BlockPos(8, 65, -2)) && first.site().facing() == Direction.EAST, "Lakeside office origin (8,65,-2) facing east to the lake");
		Set<String> ids = new HashSet<>();
		for (T3Seeds.Seed seed : seeds) {
			check(ids.add(seed.id()), "unique id " + seed.id());
			check(seed.name().length() <= 18, "name fits the Place button: " + seed.name());
			check(seed.blurb().length() <= 48, "blurb fits one 200-px line: " + seed.blurb());
			check(seed.site() != null && seed.site().facing().getAxis().isHorizontal(), "recorded site with a horizontal facing: " + seed.id());
		}
		check(T3Seeds.byId("nope") == null && T3Seeds.byId(null) == null, "unknown ids resolve to null");
	}

	private static void checkMarker() throws Exception {
		Path save = Files.createTempDirectory("t3world");
		check(T3WorldData.read(save) == null, "a plain save is not a T3 World");
		T3WorldData data = new T3WorldData("Office by the lake", "lakeside", -2085440121970745551L, true, List.of("MacBook Pro"));
		T3WorldData.write(save, data);
		check(Files.isRegularFile(save.resolve("dimensions/minecraft/overworld/data/t3craft/world.dat")), "marker lives where the overworld's SavedData storage reads t3craft:world");
		T3WorldData back = T3WorldData.read(save);
		check(back != null && back.name.equals("Office by the lake") && "lakeside".equals(back.seedId) && back.seed == data.seed && back.creative, "marker round-trips");
		check(!back.setupDone && back.origin == null && back.spawn == null && back.floors.equals(List.of("MacBook Pro")), "fresh marker has no placement yet");
		back.setupDone = true; back.origin = new BlockPos(8, 65, -2); back.rotation = Rotation.COUNTERCLOCKWISE_90; back.spawn = new BlockPos(-1, 65, -27); back.spawnYaw = 270f; back.spawnPitch = -7f;
		T3WorldData.write(save, back);
		T3WorldData again = T3WorldData.read(save);
		check(again.setupDone && again.origin.equals(back.origin) && again.rotation == Rotation.COUNTERCLOCKWISE_90 && again.spawn.equals(back.spawn) && again.spawnYaw == 270f && again.spawnPitch == -7f, "placement round-trips");
		T3WorldData random = new T3WorldData("Any", null, 42L, false, List.of());
		T3WorldData.write(save, random);
		check(T3WorldData.read(save).seedId == null, "Surprise me keeps no seed id");
	}

	private static void checkPlacement() {
		int[] outside = OfficeDesign.doorOutside();
		check(outside[0] == 25 && outside[1] == -2, "design A's door opens onto the plaza at (25, -2), got " + outside[0] + "," + outside[1]);
		check(OfficeDesign.EXTENT[0] == -2 && OfficeDesign.EXTENT[1] == 31 && OfficeDesign.EXTENT[2] == -6 && OfficeDesign.EXTENT[3] == 25, "design A footprint from its block list");
		check(OfficeDesign.plaza().size() > 800, "ground layer columns come from the design");
		// Lakeside: the door cell (25,0,-2) turned counter-clockwise about (8,65,-2): X = Ox + z, Z = Oz - x.
		OfficePlacement.Placement lakeside = OfficePlacement.plan(new BlockPos(8, 65, -2), Direction.EAST);
		check(lakeside.rotation() == Rotation.COUNTERCLOCKWISE_90, "east view = counter-clockwise quarter turn");
		check(lakeside.door().equals(new BlockPos(6, 65, -27)), "door at " + lakeside.door().toShortString());
		check(lakeside.spawn().equals(new BlockPos(-1, 65, -27)), "spawn seven blocks west of the door, got " + lakeside.spawn().toShortString());
		check(Direction.fromYRot(lakeside.spawnYaw()) == Direction.EAST, "you arrive looking east at the door");
		check(lakeside.spawnPitch() < -4 && lakeside.spawnPitch() > -12, "you look a little up at the canopy sign, got " + lakeside.spawnPitch());
		for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
			OfficePlacement.Placement p = OfficePlacement.plan(new BlockPos(100, 70, -40), facing);
			BlockPos toDoor = p.door().subtract(p.spawn());
			Direction look = Direction.fromYRot(p.spawnYaw());
			check(toDoor.equals(new BlockPos(look.getStepX() * OfficePlacement.SPAWN_DISTANCE, 0, look.getStepZ() * OfficePlacement.SPAWN_DISTANCE)),
				"facing " + facing + ": the door is " + OfficePlacement.SPAWN_DISTANCE + " blocks straight ahead of spawn");
			check(look == facing, "facing " + facing + ": you look through the door toward the view, so the glazing is straight ahead");
			check(p.door().getY() == 70 && p.spawn().getY() == 70, "door and spawn on the floor level");
			var box = OfficePlacement.bounds(p.frame(), 2);
			check(box.contains(net.minecraft.world.phys.Vec3.atCenterOf(p.door())) && box.getXsize() > 30 && box.getZsize() > 30, "facing " + facing + ": the build bounds cover the turned footprint");
		}
	}

	private static void checkFrame() {
		OfficeFrame lakeside = new OfficeFrame(new BlockPos(8, 65, -2), Rotation.COUNTERCLOCKWISE_90);
		check(lakeside.block(3, 0, 2).equals(new BlockPos(10, 65, -5)), "cells turn about the origin");
		check(lakeside.local(new BlockPos(10, 65, -5)).equals(new BlockPos(3, 0, 2)), "hit tests turn back");
		// The designer's prop sheet records desk 0's chair in the Lakeside world: (10.5, 65.0625, -4.5) yaw -90.
		var chair = lakeside.point(3.5, 0.0625, 2.5);
		check(Math.abs(chair.x - 10.5) < 1e-9 && Math.abs(chair.z + 4.5) < 1e-9, "continuous positions use the structure transform, got " + chair);
		check(Math.abs(lakeside.yaw(0) - (-90)) < 1e-6 && Float.isNaN(lakeside.yaw(Float.NaN)), "yaws turn with the office; face-the-player stays");
		var door = lakeside.state(Blocks.BIRCH_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		check(door.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST, "block states turn: the door faces the lake");
		var log = lakeside.state(Blocks.STRIPPED_BIRCH_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		check(log.getValue(BlockStateProperties.AXIS) == Direction.Axis.Z, "axis properties turn");
		var pane = lakeside.state(Blocks.GLASS_PANE.defaultBlockState().setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.SOUTH, true));
		check(pane.getValue(BlockStateProperties.EAST) && pane.getValue(BlockStateProperties.WEST) && !pane.getValue(BlockStateProperties.NORTH), "pane connections turn");
		check(lakeside.command("setblock 1 2 16 birch_wall_sign[facing=east]{front_text:{messages:[\"a\",\"facing=south\",\"\",\"\"]}}")
			.equals("setblock 24 67 -3 minecraft:birch_wall_sign[facing=north,waterlogged=false]{front_text:{messages:[\"a\",\"facing=south\",\"\",\"\"]}}"), "sign commands turn their facing and keep their text");
		check(lakeside.command("fill -2 0 -6 31 5 25 air").equals("fill 2 65 0 33 70 -33 air"), "fill corners turn");
		check(lakeside.command("setblock 3 3 3 iron_chain[axis=x]").equals("setblock 11 68 -5 minecraft:iron_chain[axis=z,waterlogged=false]"), "lamp commands turn their axis");
		OfficeFrame plain = OfficeFrame.at(new BlockPos(8, 65, -2));
		check(plain.command("setblock 1 2 16 birch_wall_sign[facing=east]").equals("setblock 9 67 14 birch_wall_sign[facing=east]"), "an unturned office keeps the plain absolute command");
		for (Rotation rotation : Rotation.values()) {
			OfficeFrame frame = new OfficeFrame(new BlockPos(-40, 70, 12), rotation);
			for (int[] cell : List.of(new int[] {0, 0}, new int[] {25, -2}, new int[] {29, 13}, new int[] {-2, 25}))
				check(frame.local(frame.block(cell[0], 3, cell[1])).equals(new BlockPos(cell[0], 3, cell[1])), rotation + ": local() inverts block()");
			var centre = frame.point(25.5, 0, 2.5);
			var cell = frame.block(25, 0, 2);
			check(Math.abs(centre.x - (cell.getX() + 0.5)) < 1e-9 && Math.abs(centre.z - (cell.getZ() + 0.5)) < 1e-9, rotation + ": a cell centre lands in the centre of the turned cell");
		}
	}

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
