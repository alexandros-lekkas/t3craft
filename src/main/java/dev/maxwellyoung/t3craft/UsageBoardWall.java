package dev.maxwellyoung.t3craft;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * The usage limits board on the office wall: {@link UsageBoardRenderer}'s image cut into one map per
 * {@link UsageBoardLayout} tile, each held by an invisible, fixed glow frame. Maps and frames are
 * client-only (map ids far above any a server hands out, negative entity ids), so it works on any
 * server and nothing is saved. Glow frames light the maps fully, so the board reads like a screen at night.
 */
final class UsageBoardWall {
	/** Client-only map ids; a server's map counter would need two billion maps to reach them. */
	static final int FIRST_MAP = 2_000_000_000;
	private static final int FIRST_ENTITY = -7_950_000;
	/** Poll T3 only while someone is close enough to read the board. */
	static final double POLL_RANGE = 64;

	static MapId mapId(int index) { return new MapId(FIRST_MAP + index); }

	private final UsageBoardSource source;
	private final ItemFrame[] frames = new ItemFrame[UsageBoardLayout.COLUMNS * UsageBoardLayout.ROWS];
	private final MapItemSavedData[] maps = new MapItemSavedData[frames.length];
	private ClientLevel level;
	private OfficeFrame planned;
	private List<UsageBoardLayout.Tile> plan = List.of();
	/** What the maps show now; {@code painted} false until the first paint in this level. */
	private UsageBoardData shown;
	private boolean painted;

	UsageBoardWall(UsageBoardSource source) { this.source = source; }

	/** Hang (or re-hang) the board in this office and repaint it when the data changed. Unpaired, it asks you to connect T3. */
	void sync(ClientLevel level, OfficeFrame frame, boolean paired, Player player) {
		if (level != this.level || !frame.equals(planned)) {
			// Another world, or the office was rebuilt elsewhere or turned: this board replaces the old one.
			clear();
			this.level = level;
			planned = frame;
			plan = UsageBoardLayout.tiles(frame);
		}
		boolean loaded = level.isLoaded(plan.getFirst().pos()) && level.isLoaded(plan.getLast().pos());
		if (paired && loaded && player.position().closerThan(UsageBoardLayout.centre(frame), POLL_RANGE)) source.refresh();
		UsageBoardData data = paired ? source.data() : null;
		if (!painted || data != shown) paint(data);
		for (int i = 0; i < plan.size(); i++) {
			ItemFrame current = frames[i];
			if (current != null && !current.isRemoved() && level.getEntity(current.getId()) == current) continue;
			var tile = plan.get(i);
			// The client drops entities with their chunk; hang this map again once it is back.
			if (!level.isLoaded(tile.pos())) continue;
			frames[i] = hang(level, tile, i);
		}
	}

	private static ItemFrame hang(ClientLevel level, UsageBoardLayout.Tile tile, int index) {
		ItemFrame frame = new GlowItemFrame(level, tile.pos(), tile.facing()) {
			// Part of the wall: nothing to aim at, hit or pop off, whatever the block behind it does.
			@Override public boolean isPickable() { return false; }
			@Override public boolean skipAttackInteraction(Entity attacker) { return true; }
			@Override public boolean survives() { return true; }
		};
		((dev.maxwellyoung.t3craft.mixin.ItemFrameAccess) frame).t3craft$fixed(true);
		frame.setInvisible(true);
		frame.setSilent(true);
		var stack = new ItemStack(Items.FILLED_MAP);
		stack.set(DataComponents.MAP_ID, mapId(index));
		frame.setItem(stack, false);
		// A stable id: re-adding replaces whatever stale copy of this tile the level still holds.
		frame.setId(FIRST_ENTITY - index);
		level.addEntity(frame);
		return frame;
	}

	/** Render the board and push each tile into its map, then have the map textures upload again. */
	private void paint(UsageBoardData data) {
		byte[] board = UsageBoardRenderer.renderMap(data, System.currentTimeMillis());
		var textures = Minecraft.getInstance().getMapTextureManager();
		for (var tile : plan) {
			int index = tile.row() * UsageBoardLayout.COLUMNS + tile.column();
			MapId id = mapId(index);
			if (maps[index] == null || level.getMapData(id) != maps[index]) {
				maps[index] = MapItemSavedData.createForClient((byte) 0, true, level.dimension());
				level.overrideMapData(id, maps[index]);
			}
			System.arraycopy(UsageBoardLayout.tileColours(board, tile.column(), tile.row()), 0, maps[index].colors, 0, 128 * 128);
			textures.update(id, maps[index]);
		}
		shown = data;
		painted = true;
	}

	/** Take the board down (office gone, rebuilt, or leaving the world). */
	void clear() {
		for (int i = 0; i < frames.length; i++) {
			if (frames[i] != null) frames[i].discard();
			frames[i] = null;
			maps[i] = null;
		}
		level = null;
		planned = null;
		plan = List.of();
		shown = null;
		painted = false;
	}

	/** Entity ids of the hung frames, for the self-test. */
	List<ItemFrame> framesForTest() {
		return java.util.Arrays.stream(frames).filter(java.util.Objects::nonNull).toList();
	}
}
