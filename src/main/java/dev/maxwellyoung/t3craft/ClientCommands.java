package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import java.util.List;

/** One budget for every mod-originated command, including books and MCP actions. */
final class ClientCommands {
	private record Command(String text, Runnable dispatched, Runnable dropped) {}
	private static final WorldBoundQueue<Command> pending = new WorldBoundQueue<>(command -> command.dropped().run());
	private static final CommandBudget budget = new CommandBudget();

	static void send(String command) {
		send(command, () -> {}, () -> {});
	}

	static void send(String command, Runnable dispatched, Runnable dropped) {
		Minecraft mc = Minecraft.getInstance();
		enqueue(mc.level, mc.getConnection(), command, dispatched, dropped);
	}

	static void enqueue(Object level, Object connection, String command, Runnable dispatched, Runnable dropped) {
		pending.add(level, connection, List.of(new Command(command, dispatched, dropped)));
	}

	static void tick(Minecraft mc) {
		budget.tick();
		dispatch(mc.level, mc.getConnection(), command -> trySend(mc, command));
	}

	static void dispatch(Object level, Object connection, java.util.function.Predicate<String> sender) {
		pending.validate(level, connection);
		if (!pending.isEmpty() && sender.test(pending.peek().text())) pending.poll().dispatched().run();
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
