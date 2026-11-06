package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Session-bound placement; local worlds execute on their integrated server. */
final class OfficeQueue {
	private record Work(String command, Runnable completed) {}
	private final WorldBoundQueue<Work> queue = new WorldBoundQueue<>();
	private long quietUntil;
	private boolean denied, inFlight;
	private int failedCommands, sentCommands;
	private Object sessionLevel, sessionConnection;
	private volatile int generation;

	boolean building() { return !queue.isEmpty() || inFlight; }
	void build(BlockPos origin, List<String> floors) {
		clear(); denied = false;
		send(origin, T3Office.blueprint(floors));
	}
	void clear() { queue.clear(); quietUntil = 0; inFlight = false; generation++; failedCommands = 0; sentCommands = 0; }
	void send(BlockPos origin, List<String> commands) { send(origin, commands, () -> {}); }
	boolean send(BlockPos origin, List<String> commands, Runnable completed) {
		Minecraft mc = Minecraft.getInstance();
		bind(mc);
		if (denied || commands.isEmpty()) return false;
		for (int i = 0; i < commands.size(); i++) queue.add(mc.level, mc.getConnection(), List.of(new Work(
			T3Office.absolute(commands.get(i), origin.getX(), origin.getY(), origin.getZ()), i == commands.size() - 1 ? completed : () -> {})));
		return true;
	}
	boolean hideFeedback(Component component) {
		String message = component.getString();
		boolean quiet = building() || System.currentTimeMillis() < quietUntil;
		if (quiet && permissionFailure(component)) {
			clear(); denied = true;
		}
		return quiet && (message.startsWith("Successfully filled") || message.startsWith("Changed the block")
			|| message.startsWith("Could not set the block") || message.startsWith("No blocks were filled"));
	}
	static boolean permissionFailure(Component message) {
		if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
			&& (text.getKey().equals("command.unknown.command") || text.getKey().equals("commands.generic.permission"))) return true;
		return message.getSiblings().stream().anyMatch(OfficeQueue::permissionFailure);
	}

	private static void report(Minecraft mc, String message) {
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}

	private void bind(Minecraft mc) {
		if (sessionLevel != mc.level || sessionConnection != mc.getConnection()) {
			clear(); denied = false; sessionLevel = mc.level; sessionConnection = mc.getConnection();
		}
	}

	void tick(Minecraft mc) {
		bind(mc);
		if (!queue.validate(mc.level, mc.getConnection())) clear();
		if (inFlight || queue.isEmpty() || mc.player == null) return;
		quietUntil = System.currentTimeMillis() + 5000;
		Work work = queue.peek();
		if (!queue.targetLoaded(OfficeSite.loaded(mc.level, BlockPos.ZERO, List.of(work.command())))) {
			clear();
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("Office placement cancelled: a target chunk unloaded. Run /t3 office again when the site is loaded."));
			return;
		}
		var server = mc.getSingleplayerServer();
		if (server == null) {
			if (ClientCommands.trySend(mc, work.command())) {
				queue.poll(); work.completed().run();
				if (++sentCommands % 32 == 0 && !queue.isEmpty()) report(mc, "Office placement: " + sentCommands + " commands sent…");
				if (queue.isEmpty()) sentCommands = 0;
			}
			return;
		}
		var clientLevel = mc.level;
		var connection = mc.getConnection();
		var dimension = clientLevel.dimension();
		int token = generation;
		inFlight = true;
		server.execute(() -> {
			var level = server.getLevel(dimension);
			boolean valid = token == generation && level != null && OfficeSite.loaded(level, BlockPos.ZERO, List.of(work.command()));
			boolean[] succeeded = {false};
			if (valid) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level)
				.withSuppressedOutput().withCallback((success, result) -> succeeded[0] = success), work.command());
			mc.execute(() -> {
				if (token != generation) return;
				if (mc.level != clientLevel || mc.getConnection() != connection) { clear(); return; }
				inFlight = false;
				if (!valid) { clear(); return; }
				if (!succeeded[0]) failedCommands++;
				queue.poll(); work.completed().run();
				if (queue.isEmpty() && failedCommands > 0) {
					report(mc, "Office placement finished with " + failedCommands + " failed commands; some blocks may be unchanged or missing.");
					failedCommands = 0;
				}
			});
		});
	}
}
