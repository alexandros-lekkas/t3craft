package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import java.util.List;

/** One budget for every mod-originated command, including books and MCP actions. */
final class ClientCommands {
	private static final WorldBoundQueue<String> pending = new WorldBoundQueue<>();
	private static final CommandBudget budget = new CommandBudget();

	static void send(String command) {
		Minecraft mc = Minecraft.getInstance();
		pending.add(mc.level, mc.getConnection(), List.of(command));
	}

	static void tick(Minecraft mc) {
		pending.validate(mc.level, mc.getConnection());
		budget.tick();
		if (!pending.isEmpty() && trySend(mc, pending.peek())) pending.poll();
	}

	static boolean trySend(Minecraft mc, String command) {
		if (mc.player == null) return false;
		// 26.3 ServerGamePacketListenerImpl: +20/command, -1/server tick, kick at >=200
		// by default (command-spam-threshold-seconds=10). Leave half the budget for the player.
		if (mc.getSingleplayerServer() == null) {
			if (!budget.acquire(System.nanoTime())) return false;
		}
		mc.player.connection.sendCommand(command);
		return true;
	}
}
