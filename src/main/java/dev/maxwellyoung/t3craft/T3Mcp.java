package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A tiny MCP server (streamable HTTP, JSON responses only) on 127.0.0.1 so agents on this
 * machine can see the player's world and, when the player allows it, run commands in it.
 * Register with: {@code claude mcp add --transport http minecraft http://127.0.0.1:25590/mcp}
 * The port comes from {@code mcpPort} in config/t3craft.json ({@code -Dt3craft.mcpPort} for dev runs); when it is
 * taken, the next free port is used and logged, so a second game or a dev client still gets a server. The bound
 * address is also written to {@code config/t3craft-mcp.json} ({@code {"url": …, "port": …, "pid": …}}) so tools never
 * have to read the log; the file is removed when that game quits.
 */
final class T3Mcp {
	/** How many ports above the preferred one are tried before giving up. */
	static final int PORT_ATTEMPTS = 10;

	private final BooleanSupplier commandsAllowed;
	private final int preferredPort;
	private final java.nio.file.Path portFile;
	private HttpServer server;
	private volatile int port;
	private final McpJsonRpc rpc = new McpJsonRpc(T3Mcp::tools, this::call);
	// Server feedback lines ("Successfully filled 49 block(s)") collected while a command is in flight.
	private final java.util.List<String> feedback = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile boolean collecting;
	// Dispatch runs on the game thread, including after the caller has timed out.
	private final Object collectionLock = new Object();
	private Object activeCall;

	/** {@code portFile}: where the bound address is published, or null to publish nothing. */
	T3Mcp(BooleanSupplier commandsAllowed, int preferredPort, java.nio.file.Path portFile) {
		this.commandsAllowed = commandsAllowed;
		this.preferredPort = preferredPort;
		this.portFile = portFile;
	}

	/** The configured port, with the {@code t3craft.mcpPort} system property winning for test and dev runs. */
	static int preferredPort(T3Config config) {
		Integer override = Integer.getInteger("t3craft.mcpPort");
		int port = override != null ? override : config.mcpPort;
		return port > 0 && port <= 65535 ? port : T3Config.DEFAULT_MCP_PORT;
	}

	/** The port actually bound, or 0 before {@link #start()} succeeded. */
	int port() { return port; }

	void start() {
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (collecting && !overlay) feedback.add(message.getString());
		});
		try {
			server = bind(preferredPort, PORT_ATTEMPTS);
			port = server.getAddress().getPort();
			server.createContext("/mcp", this::handle);
			server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "t3craft-mcp");
				thread.setDaemon(true);
				return thread;
			}));
			server.start();
			long pid = ProcessHandle.current().pid();
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
				server.stop(0);
				if (portFile != null) clearPortFile(portFile, pid);
			});
			if (port != preferredPort) T3CraftClient.LOGGER.warn("Port {} is in use (another Minecraft?); the MCP server moved to {}", preferredPort, port);
			T3CraftClient.LOGGER.info("Minecraft MCP server on {}", url(port));
			if (portFile != null && !writePortFile(portFile, port, pid)) T3CraftClient.LOGGER.warn("Could not write {}; the MCP address is only in this log", portFile);
		} catch (IOException e) {
			T3CraftClient.LOGGER.warn("Could not start the Minecraft MCP server on ports {}-{}; agents cannot reach this game", preferredPort, preferredPort + PORT_ATTEMPTS - 1, e);
		}
	}

	/**
	 * Binds 127.0.0.1 (the documented address, never the IPv6 loopback) on {@code preferred} or the first free port
	 * after it. Only an address already in use moves on; any other failure is reported at once.
	 */
	static HttpServer bind(int preferred, int attempts) throws IOException {
		InetAddress loopback = InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
		IOException last = null;
		for (int candidate = preferred; candidate < preferred + attempts && candidate <= 65535; candidate++) {
			try {
				return HttpServer.create(new InetSocketAddress(loopback, candidate), 0);
			} catch (BindException e) {
				last = e;
			}
		}
		throw last == null ? new IOException("No port to try") : last;
	}

	static String url(int port) { return "http://127.0.0.1:" + port + "/mcp"; }

	/** Publishes the bound address for tooling. Written to a temporary file first, so a reader never sees half of it. */
	static boolean writePortFile(java.nio.file.Path file, int port, long pid) {
		JsonObject json = new JsonObject();
		json.addProperty("url", url(port));
		json.addProperty("port", port);
		json.addProperty("pid", pid);
		try {
			java.nio.file.Path dir = file.toAbsolutePath().getParent();
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Path temporary = java.nio.file.Files.createTempFile(dir, ".t3craft-mcp-", ".tmp");
			try {
				java.nio.file.Files.writeString(temporary, json + "\n");
				java.nio.file.Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} finally { java.nio.file.Files.deleteIfExists(temporary); }
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** Removes the address file on quit, unless another game has written its own since. */
	static void clearPortFile(java.nio.file.Path file, long pid) {
		try {
			var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
			if (json.has("pid") && json.get("pid").getAsLong() == pid) java.nio.file.Files.deleteIfExists(file);
		} catch (IOException | RuntimeException ignored) {
			// Missing or not ours: leave it.
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!McpRequestPolicy.allows(exchange.getRequestHeaders(), port)) {
				exchange.sendResponseHeaders(403, -1);
				return;
			}
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			McpJsonRpc.Response reply;
			try (InputStream in = exchange.getRequestBody()) {
				byte[] bytes = in.readNBytes(McpJsonRpc.MAX_BODY + 1);
				reply = rpc.handle(bytes, bytes.length > McpJsonRpc.MAX_BODY);
			}
			if (reply.body() == null) {
				exchange.sendResponseHeaders(reply.status(), -1);
				return;
			}
			byte[] bytes = reply.bytes();
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(reply.status(), bytes.length);
			exchange.getResponseBody().write(bytes);
		} catch (RuntimeException e) {
			T3CraftClient.LOGGER.warn("MCP request failed", e);
		}
	}

	private static JsonArray tools() {
		JsonArray tools = new JsonArray();
		tools.add(tool("minecraft_status", "Where the player is and what they are looking at: position, facing, dimension, time, game mode, health.",
			schema(Map.of())));
		tools.add(tool("minecraft_nearby_blocks", "Counts of non-air blocks in a cube around the player, plus the block under their feet.",
			schema(Map.of("radius", prop("integer", "Half-width of the cube, 1-8 (default 4)")))));
		tools.add(tool("minecraft_say", "Show a message in the player's chat. Only the player sees it.",
			schema(Map.of("message", prop("string", "Text to show"))), "message"));
		tools.add(tool("minecraft_run_command", "Run a Minecraft command as the player, e.g. \"fill 10 64 10 14 68 14 minecraft:stone\" or "
				+ "\"setblock 3 70 -2 minecraft:redstone_lamp\" (no leading slash). Allowed unless the player turned it off, and needs "
				+ "permission on the server. The player sees every command in chat.",
			schema(Map.of("command", prop("string", "Command without the leading slash"))), "command"));
		return tools;
	}

	private JsonObject call(String name, JsonObject args) {
		try {
			String text = switch (name) {
				case "minecraft_status" -> onGameThread(T3Mcp::status);
				case "minecraft_nearby_blocks" -> {
					int radius = args.has("radius") && args.get("radius").isJsonPrimitive() ? Math.max(1, Math.min(8, args.get("radius").getAsInt())) : 4;
					yield onGameThread(() -> nearby(radius));
				}
				case "minecraft_say" -> {
					String message = string(args, "message");
					if (message == null) yield error("minecraft_say needs a \"message\" string.");
					yield onGameThread(() -> {
						agentChat(Component.literal(message).withStyle(ChatFormatting.WHITE));
						return "Shown in the player's chat.";
					});
				}
				case "minecraft_run_command" -> {
					if (!commandsAllowed.getAsBoolean()) {
						yield error("The player turned agent commands off. Ask them to run /t3 agent-build on in Minecraft.");
					}
					String command = string(args, "command");
					if (command == null || command.replaceFirst("^/", "").isBlank()) yield error("minecraft_run_command needs a \"command\" string.");
					yield runCommand(command.replaceFirst("^/", "").trim());
				}
				default -> error("Unknown tool " + name);
			};
			return text.startsWith("error: ") ? result(text.substring(7), true) : result(text, false);
		} catch (Exception e) {
			Throwable cause = e;
			while (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) cause = cause.getCause();
			return result("Minecraft did not respond: " + cause.getMessage(), true);
		}
	}

	private static String string(JsonObject args, String key) {
		var value = args.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	/** Sends the command, then waits briefly for the server's reply so the agent can check its work. */
	private synchronized String runCommand(String command) throws Exception {
		var dispatched = new java.util.concurrent.CompletableFuture<Long>();
		Object token = new Object();
		synchronized (collectionLock) { activeCall = token; }
		try {
			String sent = onGameThread(() -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.player == null) return "error: the player is not in a world.";
				agentChat(Component.literal("/" + command).withStyle(ChatFormatting.GRAY));
				ClientCommands.send(command, () -> {
					synchronized (collectionLock) {
						if (activeCall != token) return;
						feedback.clear();
						collecting = true;
						dispatched.complete(System.nanoTime());
					}
				}, () -> dispatched.completeExceptionally(new IllegalStateException("Command dropped because the world session changed.")));
				return "sent";
			});
			if (!"sent".equals(sent)) return sent;
			// Feedback usually lands within a tick or two; stop early once it has settled.
			long deadline = dispatched.get(30, TimeUnit.SECONDS) + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1500);
			int seen = -1;
			while (System.nanoTime() < deadline) {
				Thread.sleep(150);
				if (!feedback.isEmpty() && feedback.size() == seen) break;
				seen = feedback.size();
			}
		} catch (java.util.concurrent.TimeoutException e) {
			throw new IOException("Timed out waiting for Minecraft command dispatch.", e);
		} finally {
			synchronized (collectionLock) {
				if (activeCall == token) { activeCall = null; collecting = false; }
			}
		}
		if (feedback.isEmpty()) return "Sent /" + command + ". The server gave no reply (it may lack permission to show feedback); verify with minecraft_nearby_blocks.";
		return "Ran /" + command + ". Server replied:\n" + String.join("\n", feedback);
	}

	private static String status() {
		Minecraft minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		if (player == null || minecraft.level == null) return "error: the player is not in a world.";
		JsonObject s = new JsonObject();
		s.addProperty("player", player.getName().getString());
		BlockPos pos = player.blockPosition();
		s.addProperty("position", pos.getX() + " " + pos.getY() + " " + pos.getZ());
		s.addProperty("facing", player.getDirection().getName());
		s.addProperty("yaw", Math.round(player.getYRot()));
		s.addProperty("pitch", Math.round(player.getXRot()));
		s.addProperty("dimension", minecraft.level.dimension().identifier().toString());
		long day = Math.floorMod(minecraft.level.getOverworldClockTime(), 24000L);
		s.addProperty("timeOfDay", day + " (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight)");
		if (minecraft.gameMode != null) s.addProperty("gameMode", minecraft.gameMode.getPlayerMode().getName());
		s.addProperty("health", player.getHealth());
		HitResult hit = minecraft.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos target = block.getBlockPos();
			s.addProperty("lookingAt", blockId(minecraft.level.getBlockState(target)) + " at " + target.getX() + " " + target.getY() + " " + target.getZ());
		} else if (hit instanceof EntityHitResult entity) {
			s.addProperty("lookingAt", entity.getEntity().getName().getString());
		}
		return s.toString();
	}

	private static String nearby(int radius) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) return "error: the player is not in a world.";
		BlockPos center = minecraft.player.blockPosition();
		Map<String, Integer> counts = new TreeMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			BlockState state = minecraft.level.getBlockState(pos);
			if (!state.isAir()) counts.merge(blockId(state), 1, Integer::sum);
		}
		JsonObject s = new JsonObject();
		s.addProperty("center", center.getX() + " " + center.getY() + " " + center.getZ());
		s.addProperty("radius", radius);
		s.addProperty("underFeet", blockId(minecraft.level.getBlockState(center.below())));
		JsonObject blocks = new JsonObject();
		counts.forEach(blocks::addProperty);
		s.add("blocks", blocks);
		return s.toString();
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static void agentChat(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return;
		minecraft.gui.hud.getChat().addClientSystemMessage(Component.empty()
			.append(Component.literal("[agent] ").withStyle(ChatFormatting.LIGHT_PURPLE)).append(message));
	}

	private static String onGameThread(java.util.function.Supplier<String> work) throws Exception {
		return Minecraft.getInstance().submit(work).get(5, TimeUnit.SECONDS);
	}

	private static String error(String message) {
		return "error: " + message;
	}

	private static JsonObject result(String text, boolean isError) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonArray array = new JsonArray();
		array.add(content);
		JsonObject result = new JsonObject();
		result.add("content", array);
		result.addProperty("isError", isError);
		return result;
	}

	private static JsonObject tool(String name, String description, JsonObject inputSchema, String... required) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		if (required.length > 0) {
			JsonArray req = new JsonArray();
			for (String r : required) req.add(r);
			inputSchema.add("required", req);
		}
		tool.add("inputSchema", inputSchema);
		return tool;
	}

	private static JsonObject schema(Map<String, JsonObject> properties) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		properties.forEach(props::add);
		schema.add("properties", props);
		return schema;
	}

	private static JsonObject prop(String type, String description) {
		JsonObject prop = new JsonObject();
		prop.addProperty("type", type);
		prop.addProperty("description", description);
		return prop;
	}
}
