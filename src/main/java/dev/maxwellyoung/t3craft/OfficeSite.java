package dev.maxwellyoung.t3craft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Derive the affected volume from the commands, including the plaza and roof logo. */
final class OfficeSite {
	record Bounds(BlockPos min, BlockPos max) {
		String describe() { return min.toShortString() + " through " + max.toShortString() + " (inclusive)"; }
	}

	static Bounds bounds(BlockPos origin, List<String> commands) {
		int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
		int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
		for (String command : commands) {
			String[] parts = command.split(" ");
			int count = parts[0].equals("fill") ? 6 : 3;
			for (int i = 1; i <= count; i += 3) {
				int x = Integer.parseInt(parts[i]), y = Integer.parseInt(parts[i + 1]), z = Integer.parseInt(parts[i + 2]);
				minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
				maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
			}
		}
		return new Bounds(origin.offset(minX, minY, minZ), origin.offset(maxX, maxY, maxZ));
	}

	/** Whether every chunk a turned office's relative commands touch is loaded. */
	static boolean loaded(Level level, OfficeFrame frame, List<String> commands) {
		if (frame.rotation() == net.minecraft.world.level.block.Rotation.NONE) return loaded(level, frame.origin(), commands);
		List<String> absolute = new java.util.ArrayList<>();
		for (String command : commands) absolute.add(frame.command(command));
		return loaded(level, BlockPos.ZERO, absolute);
	}

	static boolean loaded(Level level, BlockPos origin, List<String> commands) {
		Bounds bounds = bounds(origin, commands);
		if (bounds.min().getY() < level.getMinY() || bounds.max().getY() > level.getMaxY()) return false;
		for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
			for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
				if (!level.isLoaded(new BlockPos(x << 4, origin.getY(), z << 4))) return false;
		return true;
	}

	/**
	 * Whether {@link #blocker} finds anything: terrain never counts, only the player's storage and builds.
	 * {@code old} is the office already standing (with its turn), or null; its own blocks never count.
	 */
	static boolean occupied(Level level, Bounds bounds, OfficeFrame old, List<String> oldBlueprint) {
		return blocker(level, bounds, old, oldBlueprint) != null;
	}

	static boolean occupied(Bounds bounds, OfficeFrame old, List<String> oldBlueprint,
		java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks,
		java.util.function.Function<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> entities) {
		return blocker(bounds, old, oldBlueprint, blocks, entities) != null;
	}

	/** Block entities world generation leaves in the open: wild bee nests, suspicious sand and gravel, creaking hearts. */
	private static boolean natural(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.entity.BlockEntity entity) {
		if (entity instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity) return state.is(net.minecraft.world.level.block.Blocks.BEE_NEST);
		return entity instanceof net.minecraft.world.level.block.entity.BrushableBlockEntity
			|| entity instanceof net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;
	}

	static BlockPos blocker(Level level, Bounds bounds, OfficeFrame old, List<String> oldBlueprint) {
		return blocker(bounds, old, oldBlueprint, level::getBlockState, level::getBlockEntity);
	}

	/**
	 * Blocks nature never places: anything crafted. Terrain, stone, ore, wood, leaves, plants, snow and water are
	 * replaced without a word, so a hill or a forest never stops the office; a wall of planks or a glass window does.
	 */
	static boolean playerMade(net.minecraft.world.level.block.state.BlockState state) {
		if (state.isAir() || state.canBeReplaced()) return false;
		var block = state.getBlock();
		if (block instanceof net.minecraft.world.level.block.StairBlock || block instanceof net.minecraft.world.level.block.SlabBlock
			|| block instanceof net.minecraft.world.level.block.FenceBlock || block instanceof net.minecraft.world.level.block.FenceGateBlock
			|| block instanceof net.minecraft.world.level.block.WallBlock || block instanceof net.minecraft.world.level.block.DoorBlock
			|| block instanceof net.minecraft.world.level.block.TrapDoorBlock || block instanceof net.minecraft.world.level.block.LadderBlock
			|| block instanceof net.minecraft.world.level.block.IronBarsBlock || block instanceof net.minecraft.world.level.block.TransparentBlock
			|| block instanceof net.minecraft.world.level.block.WoolCarpetBlock || block instanceof net.minecraft.world.level.block.BedBlock
			|| block instanceof net.minecraft.world.level.block.AnvilBlock || block instanceof net.minecraft.world.level.block.ButtonBlock
			|| block instanceof net.minecraft.world.level.block.BasePressurePlateBlock || block instanceof net.minecraft.world.level.block.BaseRailBlock
			|| block instanceof net.minecraft.world.level.block.CraftingTableBlock || block instanceof net.minecraft.world.level.block.BaseTorchBlock
			|| block instanceof net.minecraft.world.level.block.LanternBlock || block instanceof net.minecraft.world.level.block.CandleBlock) return true;
		// By name rather than tag: tags are only loaded with a world, and this also runs in the headless office check.
		String key = BuiltInRegistries.BLOCK.getKey(block).getPath();
		// Natural look-alikes stay replaceable: badlands terracotta, mossy boulders, geode basalt, quartz ore.
		if (key.equals("smooth_basalt")) return false;
		if (key.equals("cobblestone")) return true;
		for (String crafted : new String[] {"planks", "wool", "glazed_terracotta", "bricks", "banner", "concrete", "polished_", "smooth_", "cut_",
			"chiseled_", "quartz_block", "quartz_bricks", "quartz_pillar", "bookshelf", "glass"})
			if (key.contains(crafted)) return true;
		return false;
	}

	/**
	 * The first spot the build must not touch, or null when it can go ahead: a container with items, a lectern with a
	 * book, any block entity that is not the office's own (chests, furnaces, signs, beds…), or a crafted block that is
	 * not the office's own. The plaza layer and everything natural are replaceable.
	 */
	static BlockPos blocker(Bounds bounds, OfficeFrame old, List<String> oldBlueprint,
		java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks,
		java.util.function.Function<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> entities) {
		Map<BlockPos, String> existing = old == null ? Map.of() : existing(old, oldBlueprint, blocks);
		for (BlockPos pos : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
			var state = blocks.apply(pos);
			var entity = entities.apply(pos);
			boolean officeBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(existing.get(pos));
			if (entity instanceof net.minecraft.world.level.block.entity.LecternBlockEntity lectern && lectern.hasBook()) return pos.immutable();
			if (entity instanceof net.minecraft.world.Container container && (!officeBlock || !container.isEmpty())) return pos.immutable();
			if (entity != null && !officeBlock && !natural(state, entity)) return pos.immutable();
			if (pos.getY() == bounds.min().getY() || officeBlock) continue;
			if (playerMade(state)) return pos.immutable();
		}
		return null;
	}

	/**
	 * The standing office's own blocks, cell by cell, through its frame: a turned office (most T3 Worlds) is
	 * matched where its blocks really are, not where an unturned one would stand.
	 */
	static Map<BlockPos, String> existing(OfficeFrame old, List<String> blueprint,
		java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks) {
		Map<BlockPos, String> existing = new HashMap<>();
		for (String relative : blueprint) {
			String command = old.command(relative);
			String[] parts = command.split(" ");
			boolean fill = parts[0].equals("fill");
			Bounds area = bounds(BlockPos.ZERO, List.of(command));
			String block = blockId(parts[fill ? 7 : 4]);
			boolean hollow = command.endsWith(" hollow");
			for (BlockPos pos : BlockPos.betweenClosed(area.min(), area.max())) {
				boolean inside = hollow && pos.getX() > area.min().getX() && pos.getX() < area.max().getX()
					&& pos.getY() > area.min().getY() && pos.getY() < area.max().getY()
					&& pos.getZ() > area.min().getZ() && pos.getZ() < area.max().getZ();
				existing.put(pos.immutable(), inside ? "minecraft:air" : block);
			}
		}
		// Dynamic office furnishings are still office blocks at these exact positions: both lamp states and the whiteboards.
		int floors = Math.max(2, (bounds(BlockPos.ZERO, blueprint).max().getY() + 1) / T3Office.FLOOR_HEIGHT - 1);
		for (boolean night : new boolean[] {false, true}) for (String relative : OfficeDesign.lamps(floors, night)) {
			String[] parts = old.command(relative).split(" ");
			BlockPos pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
			String expected = blockId(parts[4]);
			String actual = BuiltInRegistries.BLOCK.getKey(blocks.apply(pos).getBlock()).toString();
			if (actual.equals(expected)) existing.put(pos, actual);
		}
		for (int floor = 0; floor < floors; floor++) for (int z : new int[] {14, 15, 16})
			existing.put(old.block(1, floor * T3Office.FLOOR_HEIGHT + 2, z), "minecraft:birch_wall_sign");
		return existing;
	}

	/** "birch_wall_sign[facing=east]{…}" or "minecraft:stone" → "minecraft:birch_wall_sign" / "minecraft:stone". */
	private static String blockId(String state) {
		String block = state.split("[\\[{]", 2)[0];
		return block.contains(":") ? block : "minecraft:" + block;
	}
}
