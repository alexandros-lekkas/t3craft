package dev.maxwellyoung.t3craft;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Public Streamable HTTP MCP. Never retries a write after an ambiguous network failure. */
final class T3McpBackend implements T3Backend {
	static final String LEGACY_REQUIRED = "Needs legacy connection: approvals and checkpoint review are not supported by T3 MCP yet.";
	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
	private final String url, token, owner;
	private final long expiresAt;
	private final T3Api legacy;
	private boolean initialized;
	private String session;
	private long sequence;
	T3McpBackend(T3Config.Environment env) {
		url = env.mcpUrl; token = env.mcpToken; owner = T3Config.ownerKey(env.baseUrl); expiresAt = env.mcpExpiresAt;
		legacy = env.accessToken == null || env.accessToken.isBlank() ? null : new T3Api(owner, env.accessToken);
	}
	public String ownerKey() { return owner; }
	public boolean publicMcp() { return true; }
	public boolean hasLegacy() { return legacy != null; }
	public boolean sameCredentials(T3Backend other) {
		return other instanceof T3McpBackend m && url.equals(m.url) && Objects.equals(token, m.token) && expiresAt == m.expiresAt
			&& (legacy == null ? m.legacy == null : legacy.sameCredentials(m.legacy));
	}
	public void forgetProtocol() { if (legacy != null) legacy.forgetProtocol(); }
	public int negotiatedProtocol() { return legacy == null ? 0 : legacy.negotiatedProtocol(); }
	public JsonObject adaptShell(JsonObject value) { return legacy.adaptShell(value); }
	public JsonObject adaptShellThread(JsonObject value) { return legacy.adaptShellThread(value); }
	public T3Socket openSocket(Consumer<String> closed) throws IOException, InterruptedException {
		if (legacy == null) throw new IOException(LEGACY_REQUIRED);
		return legacy.openSocket(closed);
	}
	private JsonObject rpc(String method, JsonObject params, boolean notification) throws IOException, InterruptedException {
		if (token == null || token.isBlank() || expiresAt > 0 && System.currentTimeMillis() >= expiresAt)
			throw new IOException("MCP access expired. Re-pair with the MCP URL in Connections to authorize again.");
		JsonObject body = obj("jsonrpc", "2.0"); body.addProperty("method", method); body.add("params", params);
		long id = ++sequence; if (!notification) body.addProperty("id", id);
		var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
			.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-06-18");
		if (session != null) builder.header("Mcp-Session-Id", session);
		var response = HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 401) { initialized = false; session = null; throw new IOException("MCP access expired or revoked. Re-pair with the MCP URL in Connections."); }
		if (response.statusCode() == 403) throw new IOException("MCP access is read-only or restricted. Authorize write access in the browser to perform this action.");
		if (response.statusCode() == 404) { initialized = false; session = null; }
		if (response.statusCode() / 100 != 2) throw new IOException("T3 MCP request failed (HTTP " + response.statusCode() + ").");
		response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
		if (notification) return new JsonObject();
		try {
			JsonObject reply = null;
			if (response.headers().firstValue("content-type").orElse("").contains("text/event-stream")) {
				for (String event : response.body().replace("\r\n", "\n").split("\n\n")) {
					String data = event.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).stripLeading()).reduce((a,b) -> a + "\n" + b).orElse("");
					if (data.isBlank()) continue;
					JsonObject candidate = JsonParser.parseString(data).getAsJsonObject();
					if (candidate.has("id") && candidate.get("id").getAsLong() == id) reply = candidate;
				}
			} else reply = JsonParser.parseString(response.body()).getAsJsonObject();
			if (reply == null || !reply.has("id") || reply.get("id").getAsLong() != id) throw new IOException("T3 MCP returned no matching response.");
			if (reply.has("error")) throw new IOException("T3 MCP rejected the request. Check access permissions and server compatibility.");
			return reply.getAsJsonObject("result");
		} catch (RuntimeException e) { throw new IOException("T3 MCP returned an invalid response."); }
	}
	synchronized JsonObject call(String name, JsonObject arguments) throws IOException, InterruptedException {
		if (!initialized) {
			JsonObject init = obj("protocolVersion", "2025-06-18"); init.add("capabilities", new JsonObject());
			JsonObject client = obj("name", "T3 Craft"); client.addProperty("version", "1"); init.add("clientInfo", client);
			JsonObject result = rpc("initialize", init, false);
			if (!"2025-06-18".equals(T3Protocol.string(result, "protocolVersion"))) throw new IOException("Unsupported MCP protocol version. Update T3 Craft.");
			rpc("notifications/initialized", new JsonObject(), true); initialized = true;
		}
		JsonObject params = obj("name", name); params.add("arguments", arguments);
		JsonObject result = rpc("tools/call", params, false);
		if (result.has("isError") && result.get("isError").getAsBoolean()) throw new IOException("T3 MCP could not complete " + name + ". Check access and the current thread state in T3.");
		JsonObject data = T3Protocol.object(result, "structuredContent");
		if (data == null) for (var item : T3Protocol.array(result, "content")) {
			JsonObject block = item.getAsJsonObject();
			if ("text".equals(T3Protocol.string(block, "type"))) try { data = JsonParser.parseString(block.get("text").getAsString()).getAsJsonObject(); break; }
			catch (RuntimeException ignored) {}
		}
		if (data == null || data.has("error") || data.has("_tag")) throw new IOException("T3 MCP returned an unsuccessful tool result for " + name + ".");
		return data;
	}
	private JsonArray pages(String tool, String key, JsonObject args) throws IOException, InterruptedException {
		JsonArray all = new JsonArray(); args.addProperty("limit", 100); int previous = -1;
		while (true) {
			JsonObject page = call(tool, args); all.addAll(T3Protocol.array(page, key));
			if (!page.has("nextCursor") || page.get("nextCursor").isJsonNull()) return all;
			int next = page.get("nextCursor").getAsInt(); if (next <= previous) throw new IOException("T3 MCP pagination did not advance.");
			previous = next; args.addProperty("cursor", next);
		}
	}
	public JsonObject shell() throws IOException, InterruptedException {
		JsonObject out = new JsonObject(), args = new JsonObject(); args.addProperty("allProjects", true);
		JsonArray threads = new JsonArray(); for (var value : pages("t3_thread_list", "threads", args)) threads.add(normalize(value.getAsJsonObject()));
		out.add("threads", threads); out.add("projects", pages("t3_project_list", "projects", new JsonObject())); return out;
	}
	private static JsonObject normalize(JsonObject source) {
		JsonObject out = source.deepCopy(); out.add("id", source.get("threadId"));
		out.add("modelSelection", T3Api.modelSelection(T3Protocol.string(source, "providerInstanceId"), T3Protocol.string(source, "model")));
		String status = T3Protocol.string(source, "status");
		out.addProperty("hasPendingUserInput", "waiting".equals(status));
		out.addProperty("mcpOnlyRequests", true);
		if (T3Protocol.string(source, "latestRunId") != null) {
			JsonObject turn = obj("turnId", T3Protocol.string(source, "latestRunId")); turn.addProperty("state", T3Protocol.legacyStatus(status)); out.add("latestTurn", turn);
		}
		return out;
	}
	public JsonObject thread(String id, int turns) throws IOException, InterruptedException {
		JsonObject args = obj("threadId", id); args.addProperty("view", "activity"); args.addProperty("runLimit", turns); args.addProperty("limit", 100); args.addProperty("maxCharsPerItem", 50000);
		JsonObject page = call("t3_thread_read", args), out = normalize(page.getAsJsonObject("thread"));
		JsonArray items = new JsonArray(), messages = new JsonArray(), activities = new JsonArray();
		int previous = -1;
		while (true) {
			items.addAll(T3Protocol.array(page, "items"));
			if (!page.has("hasMore") || !page.get("hasMore").getAsBoolean()) break;
			int next = page.get("nextPosition").getAsInt(); if (next <= previous) throw new IOException("T3 MCP activity pagination did not advance.");
			previous = next; args.addProperty("afterPosition", next); page = call("t3_thread_read", args);
		}
		for (var value : items) {
			JsonObject item = value.getAsJsonObject(); String type = T3Protocol.string(item, "type");
			item.add("startedAt", item.get("updatedAt")); item.add("input", item.get("text")); item.add("prompt", item.get("text"));
			if ("assistant_message".equals(type) || "user_message".equals(type)) {
				JsonObject message = obj("role", "user_message".equals(type) ? "user" : "assistant");
				message.addProperty("text", Optional.ofNullable(T3Protocol.string(item, "text")).orElse("") + (item.has("textTruncated") && item.get("textTruncated").getAsBoolean() ? "\n[Message truncated by T3 MCP]" : ""));
				message.addProperty("streaming", "running".equals(T3Protocol.string(item, "status"))); messages.add(message);
			}
		}
		JsonObject pending = obj("threadId", id); pending.addProperty("action", "list");
		for (var request : T3Protocol.array(call("t3_pending_request", pending), "requestIds")) {
			JsonObject read = obj("threadId", id); read.addProperty("action", "read"); read.add("requestId", request);
			JsonObject payload = call("t3_pending_request", read), activity = obj("kind", "user-input.requested"); activity.add("payload", payload); activities.add(activity);
		}
		// Only permission requests come from the legacy projection; questions always use MCP.
		if (legacy != null) try {
			for (var value : T3Protocol.array(legacy.thread(id, turns), "activities")) {
				String kind = T3Protocol.string(value.getAsJsonObject(), "kind"); if (kind != null && kind.startsWith("approval.")) activities.add(value);
			}
		} catch (IOException | RuntimeException ignored) { out.addProperty("legacyUnavailable", true); }
		out.add("messages", messages); out.add("activities", activities); out.add("turnItems", items); return out;
	}
	public List<T3Api.Provider> providers(T3Socket ignored) throws IOException, InterruptedException {
		List<T3Api.Provider> result = new ArrayList<>();
		for (var value : T3Protocol.array(call("orchestrator_capabilities", new JsonObject()), "providers")) {
			JsonObject p = value.getAsJsonObject();
			if (!T3Protocol.array(p, "constraints").isEmpty()) continue;
			List<T3Api.Model> models = new ArrayList<>();
			for (var m : T3Protocol.array(p, "models")) { JsonObject model = m.getAsJsonObject(); String id = T3Protocol.string(model, "id"); models.add(new T3Api.Model(id, Optional.ofNullable(T3Protocol.string(model, "label")).orElse(id))); }
			result.add(new T3Api.Provider(T3Protocol.string(p, "providerInstanceId"), Optional.ofNullable(T3Protocol.string(p, "displayName")).orElse(T3Protocol.string(p, "driverKind")), false, List.copyOf(models)));
		}
		return List.copyOf(result);
	}
	public void sendPrompt(JsonObject thread, String text, JsonObject model) throws IOException, InterruptedException {
		String id = thread.get("id").getAsString();
		if (model != null && !model.equals(thread.get("modelSelection"))) { JsonObject args = obj("threadId", id); args.add("modelSelection", model); call("t3_thread_configure", args); }
		JsonObject args = obj("threadId", id); args.addProperty("message", text); args.addProperty("mode", "queue"); args.addProperty("clientRequestId", UUID.randomUUID().toString()); call("t3_thread_send", args);
	}
	public String startThread(JsonObject template, String text, JsonObject model) throws IOException, InterruptedException {
		// Fetch detail because list rows intentionally omit worktree settings.
		JsonObject read = obj("threadId", template.get("id").getAsString());
		JsonObject detail = call("t3_thread_read", read).getAsJsonObject("thread");
		JsonObject args = obj("message", text); args.add("projectId", template.get("projectId")); args.addProperty("title", text.strip().substring(0, Math.min(60, text.strip().length())));
		args.add("modelSelection", model == null ? template.get("modelSelection") : model);
		args.add("runtimeMode", template.get("runtimeMode")); args.add("interactionMode", template.get("interactionMode"));
		String path = T3Protocol.string(detail, "worktreePath"); JsonObject workspace = obj("type", path == null ? "root" : "existing_worktree");
		if (path != null) workspace.addProperty("worktreePath", path); args.add("workspaceStrategy", workspace);
		args.addProperty("clientRequestId", UUID.randomUUID().toString()); return call("t3_thread_launch", args).get("threadId").getAsString();
	}
	public void interrupt(String id) throws IOException, InterruptedException { call("t3_thread_interrupt", obj("threadId", id)); }
	public void answerQuestions(String id, String request, JsonObject answers) throws IOException, InterruptedException {
		JsonObject args = obj("threadId", id); args.addProperty("action", "respond"); args.addProperty("requestId", request); args.add("answers", answers); call("t3_pending_request", args);
	}
	public void respondToApproval(String id, String request, String decision) throws IOException, InterruptedException {
		if (legacy == null) throw new IOException(LEGACY_REQUIRED);
		try { legacy.respondToApproval(id, request, decision); } catch (IOException | RuntimeException e) { throw new IOException(LEGACY_REQUIRED); }
	}
	public T3Api.Review review(String id) throws IOException, InterruptedException {
		if (legacy == null) throw new IOException(LEGACY_REQUIRED);
		try { return legacy.review(id); } catch (IOException | RuntimeException e) { throw new IOException(LEGACY_REQUIRED + " Check that the legacy connection is healthy and a checkpoint exists."); }
	}
	static JsonObject obj(String key, String value) { JsonObject o = new JsonObject(); o.addProperty(key, value); return o; }
}
