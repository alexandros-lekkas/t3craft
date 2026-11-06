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

	static boolean loaded(Level level, BlockPos origin, List<String> commands) {
		Bounds bounds = bounds(origin, commands);
		if (bounds.min().getY() < level.getMinY() || bounds.max().getY() > level.getMaxY()) return false;
		for (int x = bounds.min().getX() >> 4; x <= bounds.max().getX() >> 4; x++)
			for (int z = bounds.min().getZ() >> 4; z <= bounds.max().getZ() >> 4; z++)
				if (!level.isLoaded(new BlockPos(x << 4, origin.getY(), z << 4))) return false;
		return true;
	}

	/** Ignore terrain at the plaza layer and replaceable vegetation; protect player builds and containers. */
	static boolean occupied(Level level, Bounds bounds, BlockPos oldOrigin, List<String> oldBlueprint) {
		return occupied(bounds, oldOrigin, oldBlueprint, level::getBlockState, level::getBlockEntity);
	}

	static boolean occupied(Bounds bounds, BlockPos oldOrigin, List<String> oldBlueprint,
		java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks,
		java.util.function.Function<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> entities) {
		Map<BlockPos, String> existing = new HashMap<>();
		if (oldOrigin != null) for (String command : oldBlueprint) {
			String[] parts = command.split(" ");
			boolean fill = parts[0].equals("fill");
			Bounds area = bounds(oldOrigin, List.of(command));
			String block = parts[fill ? 7 : 4].split("[\\[{]", 2)[0];
			if (!block.contains(":")) block = "minecraft:" + block;
			boolean hollow = command.endsWith(" hollow");
			for (BlockPos pos : BlockPos.betweenClosed(area.min(), area.max())) {
				boolean inside = hollow && pos.getX() > area.min().getX() && pos.getX() < area.max().getX()
					&& pos.getY() > area.min().getY() && pos.getY() < area.max().getY()
					&& pos.getZ() > area.min().getZ() && pos.getZ() < area.max().getZ();
				existing.put(pos.immutable(), inside ? "minecraft:air" : block);
			}
		}
		// Dynamic office furnishings are still office blocks at these exact positions.
		if (oldOrigin != null) {

   int floors = Math.max(2, (bounds(oldOrigin, oldBlueprint).max().getY() - oldOrigin.getY() + 1) / T3Office.FLOOR_HEIGHT - 1);
   for(boolean night:new boolean[]{false,true})for(String command:OfficeDesign.lamps(floors,night)){
    String[] parts=command.split(" ");BlockPos pos=oldOrigin.offset(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
    String expected="minecraft:"+parts[4].split("\\[")[0];String actual=BuiltInRegistries.BLOCK.getKey(blocks.apply(pos).getBlock()).toString();
    if(actual.equals(expected))existing.put(pos,actual);
   }
   for(int floor=0;floor<floors;floor++)for(int z:new int[]{14,15,16})existing.put(oldOrigin.offset(1,floor*T3Office.FLOOR_HEIGHT+2,z),"minecraft:birch_wall_sign");

		}
		for (BlockPos pos : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
			var state = blocks.apply(pos);
			var entity = entities.apply(pos);
			boolean officeBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(existing.get(pos));
			if (entity instanceof net.minecraft.world.level.block.entity.LecternBlockEntity lectern && lectern.hasBook()) return true;
			if (entity instanceof net.minecraft.world.Container container && (!officeBlock || !container.isEmpty())) return true;
			if (entity != null && !officeBlock) return true;
			if (pos.getY() == bounds.min().getY() || state.canBeReplaced() || state.isAir()
				|| state.getBlock() instanceof net.minecraft.world.level.block.FlowerBlock
				|| state.is(net.minecraft.tags.BlockTags.FLOWERS)) continue;
			if (!officeBlock) return true;
		}
		return false;
	}
}
