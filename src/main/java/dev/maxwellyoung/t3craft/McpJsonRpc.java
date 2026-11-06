package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The JSON-RPC 2.0 half of the Minecraft MCP server, with no Minecraft in it so the smoke checks can drive it.
 * Every malformed message gets a proper JSON-RPC error: parse errors (-32700), invalid requests (-32600), unknown
 * methods (-32601) and bad params (-32602). Tool execution failures stay MCP tool results with {@code isError}.
 */
final class McpJsonRpc {
	static final String PROTOCOL = "2025-06-18";
	/** Requests above this are refused with a parse error; long commands fit comfortably, runaway bodies don't. */
	static final int MAX_BODY = 1 << 20;

	static final int PARSE_ERROR = -32700;
	static final int INVALID_REQUEST = -32600;
	static final int METHOD_NOT_FOUND = -32601;
	static final int INVALID_PARAMS = -32602;

	/** HTTP status plus body; a null body means 202 Accepted (notifications only). */
	record Response(int status, JsonElement body) {
		byte[] bytes() { return body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8); }
	}

	private final Supplier<JsonArray> tools;
	private final BiFunction<String, JsonObject, JsonObject> call;

	/** {@code tools} lists the advertised tools; {@code call} runs one (name, arguments) and returns a CallToolResult. */
	McpJsonRpc(Supplier<JsonArray> tools, BiFunction<String, JsonObject, JsonObject> call) {
		this.tools = tools;
		this.call = call;
	}

	/** One HTTP POST body, already read; {@code truncated} says the client sent more than {@link #MAX_BODY}. */
	Response handle(byte[] bytes, boolean truncated) {
		if (truncated) return new Response(413, error(JsonNull.INSTANCE, PARSE_ERROR, "Request body exceeds " + MAX_BODY + " bytes"));
		JsonElement body;
		try { body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)); }
		catch (RuntimeException e) { return new Response(400, error(JsonNull.INSTANCE, PARSE_ERROR, "Parse error: the body is not valid JSON")); }
		if (body == null || body.isJsonNull()) return new Response(400, error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request: expected a JSON-RPC object or batch"));
		if (body.isJsonArray()) {
			JsonArray batch = body.getAsJsonArray();
			if (batch.isEmpty()) return new Response(400, error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request: empty batch"));
			JsonArray replies = new JsonArray();
			for (JsonElement message : batch) {
				JsonObject one = respond(message);
				if (one != null) replies.add(one);
			}
			return replies.isEmpty() ? new Response(202, null) : new Response(200, replies);
		}
		if (!body.isJsonObject()) return new Response(400, error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request: expected a JSON-RPC object"));
		JsonObject reply = respond(body);
		if (reply == null) return new Response(202, null);
		// A single request that was itself malformed is a 400; a well-formed request is answered with 200 even when the method is unknown.
		boolean malformed = reply.has("error") && reply.getAsJsonObject("error").get("code").getAsInt() == INVALID_REQUEST;
		return new Response(malformed ? 400 : 200, reply);
	}

	/** One JSON-RPC message; null for notifications (which never get a reply, however odd they look). */
	JsonObject respond(JsonElement element) {
		if (!element.isJsonObject()) return error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request: a batch entry must be an object");
		JsonObject message = element.getAsJsonObject();
		JsonElement id = message.get("id");
		boolean validId = id != null && (id.isJsonNull() || id.isJsonPrimitive() && (id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber()));
		JsonElement methodElement = message.get("method");
		boolean validMethod = methodElement != null && methodElement.isJsonPrimitive() && methodElement.getAsJsonPrimitive().isString();
		if (id == null) {
			// A notification: well-formed ones are silently accepted; a response-shaped or empty object is still a request error.
			if (validMethod) return null;
			return error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request: missing method");
		}
		if (!validId) return error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request: id must be a string, number or null");
		if (!validMethod) return error(id, INVALID_REQUEST, "Invalid request: method must be a string");
		if (message.has("jsonrpc") && !(message.get("jsonrpc").isJsonPrimitive() && "2.0".equals(message.get("jsonrpc").getAsString()))) {
			return error(id, INVALID_REQUEST, "Invalid request: jsonrpc must be \"2.0\"");
		}
		JsonElement paramsElement = message.get("params");
		if (paramsElement != null && !paramsElement.isJsonNull() && !paramsElement.isJsonObject() && !paramsElement.isJsonArray()) {
			return error(id, INVALID_REQUEST, "Invalid request: params must be an object or array");
		}
		JsonObject params = paramsElement != null && paramsElement.isJsonObject() ? paramsElement.getAsJsonObject() : new JsonObject();
		String method = methodElement.getAsString();
		JsonObject result;
		switch (method) {
			case "initialize" -> {
				result = new JsonObject();
				JsonElement requested = params.get("protocolVersion");
				result.addProperty("protocolVersion", requested != null && requested.isJsonPrimitive() ? requested.getAsString() : PROTOCOL);
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
				JsonObject info = new JsonObject();
				info.addProperty("name", "minecraft");
				info.addProperty("version", "0.1.0");
				result.add("serverInfo", info);
				result.addProperty("instructions", "The user's live Minecraft game. Use minecraft_status first to learn where they are. "
					+ "Coordinates are absolute block positions. minecraft_run_command only works after the player enables it in game.");
			}
			case "ping" -> result = new JsonObject();
			case "tools/list" -> {
				result = new JsonObject();
				result.add("tools", tools.get());
			}
			case "tools/call" -> {
				JsonElement name = params.get("name");
				if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || name.getAsString().isBlank()) {
					return error(id, INVALID_PARAMS, "Invalid params: tools/call needs a string \"name\"");
				}
				JsonElement arguments = params.get("arguments");
				if (arguments != null && !arguments.isJsonNull() && !arguments.isJsonObject()) {
					return error(id, INVALID_PARAMS, "Invalid params: \"arguments\" must be an object");
				}
				result = call.apply(name.getAsString(), arguments != null && arguments.isJsonObject() ? arguments.getAsJsonObject() : new JsonObject());
			}
			default -> { return error(id, METHOD_NOT_FOUND, "Method not found: " + method); }
		}
		JsonObject reply = new JsonObject();
		reply.addProperty("jsonrpc", "2.0");
		reply.add("id", id);
		reply.add("result", result);
		return reply;
	}

	static JsonObject error(JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject reply = new JsonObject();
		reply.addProperty("jsonrpc", "2.0");
		reply.add("id", id == null ? JsonNull.INSTANCE : id);
		reply.add("error", error);
		return reply;
	}
}
