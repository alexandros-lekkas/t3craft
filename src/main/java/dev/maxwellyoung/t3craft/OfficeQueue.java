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
		Minecraft mc = Minecraft.getInstance(); bind(mc);
  var server = mc.getSingleplayerServer();
  if(server == null) { send(origin, T3Office.blueprint(floors)); return; }
  var level = mc.level; int token=generation; inFlight=true;
  server.execute(() -> {
   try { var target=server.getLevel(level.dimension());
    if(token==generation && target!=null && OfficeSite.loaded(target,origin,T3Office.blueprint(floors))) OfficePlacement.build(target,OfficeFrame.at(origin),floors);
   } finally { mc.execute(()->{if(token==generation)inFlight=false;}); }
  });
	}
	void clear() { queue.clear(); quietUntil = 0; inFlight = false; generation++; failedCommands = 0; sentCommands = 0; }
	void send(BlockPos origin, List<String> commands) { send(OfficeFrame.at(origin), commands, () -> {}); }
	boolean send(OfficeFrame frame, List<String> commands, Runnable completed) {
		Minecraft mc = Minecraft.getInstance();
		bind(mc);
		if (denied || commands.isEmpty()) return false;
		for (int i = 0; i < commands.size(); i++) queue.add(mc.level, mc.getConnection(), List.of(new Work(
			frame.command(commands.get(i)), i == commands.size() - 1 ? completed : () -> {})));
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

	/** Vanilla reports unchanged setblock commands as failures; matching lamps are already complete. */
	private static boolean alreadyPlaced(net.minecraft.server.level.ServerLevel level, String command) {
		String[] parts = command.split(" ");
		if (parts.length != 5 || !parts[0].equals("setblock")) return false;
		try {
			var pos = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
			var state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
				level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
				new com.mojang.brigadier.StringReader(parts[4]), false).blockState();
			return level.getBlockState(pos).equals(state);
		} catch (NumberFormatException | com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
			return false;
		}
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
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal("Office placement cancelled: a target chunk unloaded. Run /t3 office build again when the site is loaded."));
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
			boolean[] succeeded = {valid && alreadyPlaced(level, work.command())};
			if (valid && !succeeded[0]) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level)
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
