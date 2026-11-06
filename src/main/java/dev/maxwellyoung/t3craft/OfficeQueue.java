package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Client side of the office: sends its build and update commands a few per tick, quietly. */
final class OfficeQueue {
	private final WorldBoundQueue<String> queue = new WorldBoundQueue<>();
	private long quietUntil;

	boolean building() {
		return !queue.isEmpty();
	}

	/** Queues the whole building. Needs command permission (operator) on the server. */
	void build(BlockPos origin, List<String> floors) {
		queue.clear();
		send(origin, T3Office.blueprint(floors));
	}

	void clear() { queue.clear(); quietUntil = 0; }

	void send(BlockPos origin, List<String> commands) {
		Minecraft minecraft = Minecraft.getInstance();
		queue.add(minecraft.level, minecraft.getConnection(), commands.stream()
			.map(command -> T3Office.absolute(command, origin.getX(), origin.getY(), origin.getZ())).toList());
	}

	/**
	 * While building, the server answers every fill with "Successfully filled…". Those lines are
	 * noise; errors (no permission, unknown block) still come through. "Could not set the block"
	 * is the whiteboard being wiped when it's already blank.
	 */
	boolean hideFeedback(String message) {
		boolean quiet = !queue.isEmpty() || System.currentTimeMillis() < quietUntil;
		return quiet && (message.startsWith("Successfully filled") || message.startsWith("Changed the block")
			|| message.startsWith("Could not set the block") || message.startsWith("No blocks were filled"));
	}

	void tick(Minecraft minecraft) {
		if (!queue.validate(minecraft.level, minecraft.getConnection())) quietUntil = 0;
		if (queue.isEmpty() || minecraft.player == null) return;
		quietUntil = System.currentTimeMillis() + 3000;
		for (int i = 0; i < 6 && !queue.isEmpty(); i++) {
			if (!OfficeSite.loaded(minecraft.level, BlockPos.ZERO, List.of(queue.peek()))) return;
			minecraft.player.connection.sendCommand(queue.poll());
		}
	}
}
