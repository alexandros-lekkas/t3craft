package dev.maxwellyoung.t3craft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Minimal Chrome DevTools Protocol client over one page target's WebSocket: request/response by id plus events.
 * The static builders produce the exact messages the laptop sends, so they can be checked without a browser.
 */
final class Cdp implements WebSocket.Listener, AutoCloseable {
	private final AtomicInteger ids = new AtomicInteger();
	private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	/** Method per in-flight id, so a protocol error can be logged with the call that caused it. */
	private final Map<Integer, String> methods = new ConcurrentHashMap<>();
	private final StringBuilder buffer = new StringBuilder();
	private final BiConsumer<String, JsonObject> events;
	private volatile WebSocket socket;
	private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
	volatile boolean closed;

	private Cdp(BiConsumer<String, JsonObject> events) { this.events = events; }

	static Cdp connect(HttpClient http, String webSocketUrl, BiConsumer<String, JsonObject> events) throws Exception {
		Cdp cdp = new Cdp(events);
		cdp.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create(webSocketUrl), cdp).get(10, TimeUnit.SECONDS);
		return cdp;
	}

	/** Sends {@code method}; the future completes with its result or fails with the protocol error. */
	synchronized CompletableFuture<JsonObject> call(String method, JsonObject params) {
		int id = ids.incrementAndGet();
		CompletableFuture<JsonObject> result = new CompletableFuture<>();
		if (closed) { result.completeExceptionally(new IllegalStateException("browser closed")); return result; }
		pending.put(id, result);
		methods.put(id, method);
		String text = message(id, method, params).toString();
		sending = sending.handle((ok, error) -> null).thenCompose(ignored -> socket.sendText(text, true))
			.whenComplete((ok, error) -> { if (error != null) fail(id, error); });
		return result.orTimeout(15, TimeUnit.SECONDS).whenComplete((ok, error) -> { pending.remove(id); methods.remove(id); });
	}

	/** Fire and forget: input and acks must never block the render thread. Failures are logged, never swallowed silently. */
	void send(String method, JsonObject params) {
		call(method, params).exceptionally(error -> {
			// Protocol errors were already logged as warnings when Chrome answered; the rest is a timeout or a closed tab.
			if (!(error instanceof ProtocolException)) T3Log.LOGGER.debug("CDP {} did not complete: {}", method, error.toString());
			return null;
		});
	}

	/** Chrome rejected a command ({@code {"code":…,"message":…}}). */
	static final class ProtocolException extends IllegalStateException {
		ProtocolException(String method, String error) { super(method + " failed: " + error); }
	}

	JsonObject callSync(String method, JsonObject params) throws Exception { return call(method, params).get(15, TimeUnit.SECONDS); }

	/** Evaluates JavaScript in the page and returns its JSON value (null when undefined). */
	JsonElement eval(String expression) throws Exception {
		JsonObject result = callSync("Runtime.evaluate", obj("expression", expression, "returnByValue", true, "awaitPromise", true));
		JsonObject value = result.getAsJsonObject("result");
		return value == null ? null : value.get("value");
	}

	private void fail(int id, Throwable error) {
		methods.remove(id);
		CompletableFuture<JsonObject> future = pending.remove(id);
		if (future != null) future.completeExceptionally(error);
	}

	@Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

	@Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		buffer.append(data);
		if (last) {
			try {
				JsonObject message = JsonParser.parseString(buffer.toString()).getAsJsonObject();
				if (message.has("id")) {
					int id = message.get("id").getAsInt();
					CompletableFuture<JsonObject> future = pending.remove(id);
					String method = methods.remove(id);
					if (message.has("error")) {
						// Every rejected command is visible in the log: a silently dropped click or keystroke is a bug nobody can see.
						T3Log.LOGGER.warn("CDP {} rejected: {}", method, message.get("error"));
						if (future != null) future.completeExceptionally(new ProtocolException(method, message.get("error").toString()));
					} else if (future != null) future.complete(message.has("result") ? message.getAsJsonObject("result") : new JsonObject());
				} else if (message.has("method")) {
					events.accept(message.get("method").getAsString(), message.has("params") ? message.getAsJsonObject("params") : new JsonObject());
				}
			} catch (RuntimeException e) {
				T3Log.LOGGER.warn("CDP message ignored: {}", e.getMessage());
			} finally {
				buffer.setLength(0);
			}
		}
		webSocket.request(1);
		return null;
	}

	@Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) { shutdown(); return null; }
	@Override public void onError(WebSocket webSocket, Throwable error) { shutdown(); }

	private void shutdown() {
		closed = true;
		pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("browser closed")));
		pending.clear();
		methods.clear();
		events.accept("t3craft.closed", new JsonObject());
	}

	@Override public void close() {
		closed = true;
		WebSocket current = socket;
		if (current != null) try { current.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (RuntimeException ignored) {}
	}

	// ----- Message builders (pure; checked by UiChecks) -----

	static JsonObject message(int id, String method, JsonObject params) {
		JsonObject message = new JsonObject();
		message.addProperty("id", id);
		message.addProperty("method", method);
		message.add("params", params == null ? new JsonObject() : params);
		return message;
	}

	static JsonObject obj(Object... pairs) {
		JsonObject object = new JsonObject();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			String key = pairs[i].toString();
			Object value = pairs[i + 1];
			switch (value) {
				case null -> {}
				case JsonElement element -> object.add(key, element);
				case Boolean b -> object.addProperty(key, b);
				case Number number -> object.addProperty(key, number);
				case Character c -> object.addProperty(key, c);
				default -> object.addProperty(key, value.toString());
			}
		}
		return object;
	}

	static JsonObject metrics(WebViewport viewport) {
		return obj("width", viewport.cssWidth(), "height", viewport.cssHeight(), "deviceScaleFactor", viewport.deviceScaleFactor(),
			"mobile", false, "screenWidth", viewport.cssWidth(), "screenHeight", viewport.cssHeight());
	}

	/** Frames arrive at exactly the physical size, JPEG quality 92 (the settle frame is PNG). */
	static JsonObject screencast(WebViewport viewport) {
		return obj("format", "jpeg", "quality", 92, "maxWidth", viewport.physicalWidth(), "maxHeight", viewport.physicalHeight(), "everyNthFrame", 1);
	}

	/** A screencast too small to draw, kept only so Chrome keeps telling us when the page changed (see WebPage). */
	static JsonObject trigger() { return obj("format", "jpeg", "quality", 10, "maxWidth", 32, "maxHeight", 32, "everyNthFrame", 1); }

	/** CDP modifier bitmask: Alt 1, Ctrl 2, Meta 4, Shift 8. Takes GLFW modifier bits (Shift 1, Ctrl 2, Alt 4, Super 8). */
	static int modifiers(int glfw) {
		int out = 0;
		if ((glfw & 4) != 0) out |= 1;
		if ((glfw & 2) != 0) out |= 2;
		if ((glfw & 8) != 0) out |= 4;
		if ((glfw & 1) != 0) out |= 8;
		return out;
	}

	static String button(int glfwButton) {
		return switch (glfwButton) { case 0 -> "left"; case 1 -> "right"; case 2 -> "middle"; default -> "none"; };
	}

	/** Bitmask of pressed buttons for Input.dispatchMouseEvent: left 1, right 2, middle 4. */
	static int buttonMask(int glfwButton) {
		return switch (glfwButton) { case 0 -> 1; case 1 -> 2; case 2 -> 4; default -> 0; };
	}

	static JsonObject mouse(String type, double x, double y, int glfwButton, int buttons, int clickCount, int glfwMods) {
		return obj("type", type, "x", x, "y", y, "button", type.equals("mouseMoved") && buttons == 0 ? "none" : button(glfwButton),
			"buttons", buttons, "clickCount", clickCount, "modifiers", modifiers(glfwMods));
	}

	/** Wheel: Minecraft reports notches (+1 = away from the user); the page scrolls 100 CSS px per notch. */
	static JsonObject wheel(double x, double y, double notchesX, double notchesY, int glfwMods) {
		return obj("type", "mouseWheel", "x", x, "y", y, "deltaX", -notchesX * 100, "deltaY", -notchesY * 100, "modifiers", modifiers(glfwMods));
	}

	static JsonObject insertText(String text) { return obj("text", text); }

	/** A named (non-text) key, or null when the key types text and should go through insertText instead. */
	record Key(String key, String code, int windowsCode, String text) {}

	static Key key(int glfwKey) {
		return switch (glfwKey) {
			case 257, 335 -> new Key("Enter", "Enter", 13, "\r");
			case 259 -> new Key("Backspace", "Backspace", 8, null);
			case 258 -> new Key("Tab", "Tab", 9, null);
			case 261 -> new Key("Delete", "Delete", 46, null);
			case 262 -> new Key("ArrowRight", "ArrowRight", 39, null);
			case 263 -> new Key("ArrowLeft", "ArrowLeft", 37, null);
			case 264 -> new Key("ArrowDown", "ArrowDown", 40, null);
			case 265 -> new Key("ArrowUp", "ArrowUp", 38, null);
			case 266 -> new Key("PageUp", "PageUp", 33, null);
			case 267 -> new Key("PageDown", "PageDown", 34, null);
			case 268 -> new Key("Home", "Home", 36, null);
			case 269 -> new Key("End", "End", 35, null);
			default -> {
				// Letters and digits only matter with Ctrl/Cmd held (shortcuts); plain typing arrives via charTyped.
				if (glfwKey >= 65 && glfwKey <= 90) yield new Key(String.valueOf((char) (glfwKey + 32)), "Key" + (char) glfwKey, glfwKey, null);
				if (glfwKey >= 48 && glfwKey <= 57) yield new Key(String.valueOf((char) glfwKey), "Digit" + (char) glfwKey, glfwKey, null);
				yield null;
			}
		};
	}

	/** Editing commands macOS Chrome only runs when told to (headless has no menu bar to supply them). */
	static String macCommand(Key key, int cdpModifiers) {
		if (key == null || (cdpModifiers & 4) == 0) return null;
		boolean shift = (cdpModifiers & 8) != 0;
		return switch (key.code()) {
			case "KeyA" -> "selectAll";
			case "KeyC" -> "copy";
			case "KeyX" -> "cut";
			case "KeyV" -> "paste";
			case "KeyZ" -> shift ? "redo" : "undo";
			case "ArrowLeft" -> shift ? "moveToBeginningOfLineAndModifySelection" : "moveToBeginningOfLine";
			case "ArrowRight" -> shift ? "moveToEndOfLineAndModifySelection" : "moveToEndOfLine";
			case "Backspace" -> "deleteToBeginningOfLine";
			default -> null;
		};
	}

	static JsonObject keyEvent(String type, Key key, int glfwMods, boolean mac) {
		int mods = modifiers(glfwMods);
		JsonObject event = obj("type", type, "key", key.key(), "code", key.code(), "windowsVirtualKeyCode", key.windowsCode(),
			"nativeVirtualKeyCode", key.windowsCode(), "modifiers", mods);
		if (type.equals("keyDown") && key.text() != null && (mods & ~8) == 0) { event.addProperty("text", key.text()); event.addProperty("unmodifiedText", key.text()); }
		String command = mac && type.equals("keyDown") ? macCommand(key, mods) : null;
		if (command != null) {
			com.google.gson.JsonArray commands = new com.google.gson.JsonArray();
			commands.add(command);
			event.add("commands", commands);
		}
		return event;
	}
}
