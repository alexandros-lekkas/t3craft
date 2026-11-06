package dev.maxwellyoung.t3craft;

import com.google.gson.*;
import java.io.IOException;
import static dev.maxwellyoung.t3craft.T3McpException.Kind.*;
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
 private final String access;
 private volatile String mcpError, legacyError;
	private final T3Api legacy;
	private boolean initialized;
	private String session, authBlocked;
 static final String READ_ONLY = "T3 approved T3 Craft for Read only access, so it cannot change anything. Re-pair this machine with its MCP URL and choose Full access on T3's consent page.";
 static final String MODE_LIMIT = "T3 refused: this thread runs with more access than T3 Craft was approved for. Re-pair this machine with its MCP URL and choose Full access on T3's consent page.";
 private static final String AUTH_MESSAGE = "T3 MCP access expired or was revoked. Re-pair this machine with its MCP URL in Connections.";
 private static final class SessionLost extends IOException {}
 private static T3McpException failure(T3McpException.Kind kind, boolean safe, String message) { return new T3McpException(kind, null, safe, message); }
 private static String cap(String text, int limit) { return text == null ? "Unknown error" : text.substring(0, Math.min(limit, text.length())); }
 private static String serverMessage(String body) {
  try { JsonObject value = JsonParser.parseString(body).getAsJsonObject(); JsonObject error = T3Protocol.object(value, "error"); return T3Protocol.string(error == null ? value : error, "message"); }
  catch (RuntimeException ignored) { return null; }
 }
 private static String verb(String name) { return switch (name) {
  case "t3_thread_send" -> "send the prompt"; case "t3_thread_launch" -> "start the thread";
  case "t3_thread_interrupt" -> "stop the thread"; case "t3_pending_request" -> "answer the question";
  case "t3_thread_configure" -> "change the model"; default -> "load threads";
 }; }
	private long sequence;
 private static final class History {
  final LinkedHashMap<String, JsonObject> items = new LinkedHashMap<>();
  Integer nextPosition;
 }
 private final Map<String, History> histories = new LinkedHashMap<>(16, 0.75f, true) {
  @Override protected boolean removeEldestEntry(Map.Entry<String, History> eldest) { return size() > 64; }
 };
	T3McpBackend(T3Config.Environment env) {
		url = env.mcpUrl; token = env.mcpToken; owner = T3Config.ownerKey(env.baseUrl); expiresAt = env.mcpExpiresAt; access = env.mcpScope;
		legacy = env.accessToken == null || env.accessToken.isBlank() ? null : new T3Api(owner, env.accessToken);
	}
 public Legs legs() { return new Legs(true, mcpError, legacy != null, legacyError, expiresAt, access); }
 @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException, InterruptedException; }
 private <T> T mcp(IoSupplier<T> op) throws IOException, InterruptedException {
  try { T value = op.get(); mcpError = null; return value; }
  catch (T3McpException e) { mcpError = e.getMessage(); throw e; }
 }
 private <T> T viaLegacy(IoSupplier<T> op) throws IOException, InterruptedException {
  try { T value = op.get(); legacyError = null; return value; }
  catch (IOException | RuntimeException e) { legacyError = e.getMessage() == null ? "Legacy connection failed." : e.getMessage(); throw e; }
 }
 private <T> T read(IoSupplier<T> primary, IoSupplier<T> fallback) throws IOException, InterruptedException {
  try { return mcp(primary); } catch (T3McpException e) {
   if (fallback == null) throw e;
   try { return viaLegacy(fallback); } catch (IOException e2) { throw new IOException("MCP: " + mcpError + " · Legacy: " + legacyError); }
  }
 }
 private <T> T write(IoSupplier<T> primary, IoSupplier<T> fallback) throws IOException, InterruptedException {
  if (mcpError != null && fallback != null) return viaLegacy(fallback);
  try { return mcp(primary); } catch (T3McpException e) {
   if (fallback == null || !e.notExecuted || e.kind == TOOL) throw e;
   return viaLegacy(fallback);
  }
 }
 private static JsonObject servedByLegacy(JsonObject value) { value.addProperty("servedBy", "legacy"); return value; }
 public JsonObject shell() throws IOException, InterruptedException { return read(this::mcpShell, legacy == null ? null : () -> servedByLegacy(legacy.shell())); }
 public JsonObject thread(String id, int turns) throws IOException, InterruptedException { return read(() -> mcpThread(id, turns), legacy == null ? null : () -> servedByLegacy(legacy.thread(id, turns))); }
 public List<T3Api.Provider> providers(T3Socket socket) throws IOException, InterruptedException { return read(this::mcpProviders, legacy == null || socket == null ? null : () -> legacy.providers(socket)); }
 public void sendPrompt(JsonObject thread, String text, JsonObject model) throws IOException, InterruptedException { write(() -> { mcpSendPrompt(thread, text, model); return null; }, legacy == null ? null : () -> { legacy.sendPrompt(thread, text, model); return null; }); }
 public String startThread(JsonObject template, String text, JsonObject model) throws IOException, InterruptedException { return write(() -> mcpStartThread(template, text, model), legacy == null ? null : () -> legacy.startThread(template, text, model)); }
 public void interrupt(String id) throws IOException, InterruptedException { write(() -> { mcpInterrupt(id); return null; }, legacy == null ? null : () -> { legacy.interrupt(id); return null; }); }
 public void answerQuestions(String id, String request, JsonObject answers) throws IOException, InterruptedException { write(() -> { mcpAnswerQuestions(id, request, answers); return null; }, legacy == null ? null : () -> { legacy.answerQuestions(id, request, answers); return null; }); }
	public String ownerKey() { return owner; }
	public boolean publicMcp() { return true; }
	public boolean hasLegacy() { return legacy != null; }
	public boolean sameCredentials(T3Backend other) {
		return other instanceof T3McpBackend m && url.equals(m.url) && Objects.equals(token, m.token) && expiresAt == m.expiresAt
			&& (legacy == null ? m.legacy == null : legacy.sameCredentials(m.legacy));
	}
	public synchronized void forgetProtocol() { authBlocked = null; initialized = false; session = null; if (legacy != null) legacy.forgetProtocol(); }
	public void forgetLegacyProtocol() { if (legacy != null) legacy.forgetProtocol(); }
	public int negotiatedProtocol() { return legacy == null ? 0 : legacy.negotiatedProtocol(); }
	public JsonObject adaptShell(JsonObject value) { return legacy == null ? value : legacy.adaptShell(value); }
	public JsonObject adaptShellThread(JsonObject value) { return legacy == null ? value : legacy.adaptShellThread(value); }
	public T3Socket openSocket(Consumer<String> closed) throws IOException, InterruptedException {
		if (legacy == null) throw new IOException(LEGACY_REQUIRED);
		return viaLegacy(() -> legacy.openSocket(closed));
	}
	private JsonObject rpc(String method, JsonObject params, boolean notification) throws IOException, InterruptedException {
		if (token == null || token.isBlank() || expiresAt > 0 && System.currentTimeMillis() >= expiresAt)
			throw failure(AUTH, true, AUTH_MESSAGE);
		if (authBlocked != null) throw failure(AUTH, true, authBlocked);
		if ("initialize".equals(method)) session = null;
		JsonObject body = obj("jsonrpc", "2.0"); body.addProperty("method", method); body.add("params", params);
		long id = ++sequence; if (!notification) body.addProperty("id", id);
		var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
			.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-06-18");
		if (session != null) builder.header("Mcp-Session-Id", session);
        HttpResponse<String> response;
        try { response = HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString()); }
        catch (java.net.ConnectException | HttpConnectTimeoutException e) { throw failure(UNREACHABLE, true, "Could not reach T3 MCP at " + URI.create(url).getAuthority() + ". Check that T3 Code is running."); }
        catch (IOException e) { throw failure(AMBIGUOUS, false, "T3 MCP did not answer in time; the action may still have happened. Check T3 before retrying."); }
        int status = response.statusCode();
        if (status == 401) { initialized = false; session = null; authBlocked = AUTH_MESSAGE; throw failure(AUTH, true, authBlocked); }
        if (status == 404 && session != null) { initialized = false; session = null; throw new SessionLost(); }
        String message = serverMessage(response.body());
        if (status == 400 || status == 406 || status == 415) {
            if (status == 400) { initialized = false; session = null; }
            throw failure(REJECTED, true, "T3 MCP rejected the request" + (message == null ? " (HTTP " + status + ")" : ": " + cap(message, 300)));
        }
        if (status == 403) throw failure(REJECTED, true, "T3 refused the MCP request (HTTP 403)" + (message == null ? "" : ": " + cap(message, 300)));
        if (status / 100 != 2) throw failure("tools/call".equals(method) ? AMBIGUOUS : PROTOCOL, false, "T3 MCP request failed (HTTP " + status + ").");
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
			if (reply == null || !reply.has("id") || reply.get("id").getAsLong() != id) throw failure(PROTOCOL, false, "T3 MCP returned no matching response.");
			if (reply.has("error")) throw failure(REJECTED, true, "T3 MCP rejected " + ("tools/call".equals(method) ? T3Protocol.string(params, "name") : method) + ": " + cap(serverMessage(reply.toString()), 300));
			return reply.getAsJsonObject("result");
		} catch (RuntimeException e) { throw failure(PROTOCOL, false, "T3 MCP returned an invalid response."); }
	}
	synchronized JsonObject call(String name, JsonObject arguments) throws IOException, InterruptedException {
  for (int attempt = 0; ; attempt++) try { return callOnce(name, arguments); }
  catch (SessionLost e) { if (attempt > 0) throw failure(PROTOCOL, true, "T3 MCP session could not be re-established."); }
  catch (RuntimeException e) { throw failure(PROTOCOL, false, "T3 MCP returned an invalid tool response."); }
 }
 private JsonObject callOnce(String name, JsonObject arguments) throws IOException, InterruptedException {
		if (!initialized) {
			JsonObject init = obj("protocolVersion", "2025-06-18"); init.add("capabilities", new JsonObject());
			JsonObject client = obj("name", "T3 Craft"); client.addProperty("version", "1"); init.add("clientInfo", client);
			JsonObject result = rpc("initialize", init, false);
			if (!"2025-06-18".equals(T3Protocol.string(result, "protocolVersion"))) throw failure(PROTOCOL, true, "Unsupported MCP protocol version. Update T3 Craft.");
			rpc("notifications/initialized", new JsonObject(), true); initialized = true;
		}
		JsonObject params = obj("name", name); params.add("arguments", arguments);
		JsonObject result = rpc("tools/call", params, false);
        if (result.has("isError") && result.get("isError").getAsBoolean()) {
            String text = null, code = null;
            for (var item : T3Protocol.array(result, "content")) if ("text".equals(T3Protocol.string(item.getAsJsonObject(), "type"))) { text = T3Protocol.string(item.getAsJsonObject(), "text"); break; }
            try { JsonObject error = JsonParser.parseString(text).getAsJsonObject(); code = T3Protocol.string(error, "code"); text = Optional.ofNullable(T3Protocol.string(error, "message")).orElse(text); } catch (RuntimeException ignored) {}
            throw new T3McpException("capability_denied".equals(code) || "runtime_mode_escalation_denied".equals(code) || "interaction_mode_escalation_denied".equals(code) ? DENIED : TOOL, code, true,
                switch (code == null ? "" : code) {
                    case "capability_denied" -> READ_ONLY;
                    case "runtime_mode_escalation_denied", "interaction_mode_escalation_denied" -> MODE_LIMIT;
                    case "thread_not_found" -> "This thread no longer exists in T3.";
                    default -> "T3 could not " + verb(name) + ": " + cap(text, 300);
                });
        }
		JsonObject data = T3Protocol.object(result, "structuredContent");
		if (data == null) for (var item : T3Protocol.array(result, "content")) {
			JsonObject block = item.getAsJsonObject();
			if ("text".equals(T3Protocol.string(block, "type"))) try { data = JsonParser.parseString(block.get("text").getAsString()).getAsJsonObject(); break; }
			catch (RuntimeException ignored) {}
		}
		if (data == null || data.has("_tag")) throw failure(PROTOCOL, false, "T3 MCP returned an unsuccessful tool result for " + name + ".");
		return data;
	}
	private JsonArray pages(String tool, String key, JsonObject args) throws IOException, InterruptedException {
		JsonArray all = new JsonArray(); args.addProperty("limit", 100); int previous = -1;
		while (true) {
			JsonObject page = call(tool, args); all.addAll(T3Protocol.array(page, key));
			if (!page.has("nextCursor") || page.get("nextCursor").isJsonNull()) return all;
			int next = page.get("nextCursor").getAsInt(); if (next <= previous) throw failure(PROTOCOL, false, "T3 MCP pagination did not advance.");
			previous = next; args.addProperty("cursor", next);
		}
	}
	private JsonObject mcpShell() throws IOException, InterruptedException {
		JsonObject out = new JsonObject(), args = new JsonObject(); args.addProperty("allProjects", true); args.addProperty("includeSubagents", false);
		JsonArray threads = new JsonArray(); Set<String> listed = new HashSet<>();
        for (var value : pages("t3_thread_list", "threads", args)) { JsonObject row = normalize(value.getAsJsonObject()); threads.add(row); listed.add(T3Protocol.string(row, "id")); }
        // Main rows exclude children. Fetch only waiting children separately so Decisions can route to their own IDs.
        JsonObject waiting = new JsonObject(); waiting.addProperty("allProjects", true); waiting.addProperty("includeSubagents", true);
        JsonArray statuses = new JsonArray(); statuses.add("waiting"); waiting.add("statuses", statuses);
        for (var value : pages("t3_thread_list", "threads", waiting)) {
            JsonObject row = normalize(value.getAsJsonObject());
            if (listed.add(T3Protocol.string(row, "id"))) { row.addProperty("childRequestsOnly", true); threads.add(row); }
        }
        // Public thread_list never returns side chats, even with includeSubagents:true.
        // Legacy shell supplies their IDs and pending flags when the companion connection exists.
        if (legacy != null) try {
            for (var value : T3Protocol.array(viaLegacy(legacy::shell), "threads")) {
                JsonObject child = value.getAsJsonObject();
                if (T3Visibility.child(child) && (T3Visibility.flag(child, "hasPendingApprovals") || T3Visibility.flag(child, "hasPendingUserInput"))
                    && listed.add(T3Protocol.string(child, "id"))) threads.add(child);
            }
        } catch (IOException | RuntimeException ignored) {}
		out.add("threads", threads); out.add("projects", pages("t3_project_list", "projects", new JsonObject())); return out;
	}
	private static JsonObject normalize(JsonObject source) {
		JsonObject out = source.deepCopy(); out.add("id", source.get("threadId"));
		out.add("modelSelection", T3Api.modelSelection(T3Protocol.string(source, "providerInstanceId"), T3Protocol.string(source, "model")));
		String status = T3Protocol.string(source, "status");
		out.addProperty("hasPendingUserInput", "waiting".equals(status));
		if (T3Protocol.string(source, "latestRunId") != null) {
			JsonObject turn = obj("turnId", T3Protocol.string(source, "latestRunId")); turn.addProperty("state", T3Protocol.legacyStatus(status)); out.add("latestTurn", turn);
		}
		return out;
	}
	private synchronized JsonObject mcpThread(String id, int turns) throws IOException, InterruptedException {
        History history = histories.computeIfAbsent(id, ignored -> new History());
        JsonObject args = obj("threadId", id); args.addProperty("view", "activity"); args.addProperty("runLimit", Math.max(1, Math.min(50, turns))); args.addProperty("limit", 100); args.addProperty("maxCharsPerItem", 50000);
        if (history.nextPosition != null) args.addProperty("afterPosition", history.nextPosition);
        JsonObject page = call("t3_thread_read", args), out = normalize(page.getAsJsonObject("thread"));
        // Positions are stable; refresh in-progress items whose text can change in place.
        for (var entry : new ArrayList<>(history.items.entrySet())) {
            String status = T3Protocol.string(entry.getValue(), "status");
            if (status != null && java.util.Set.of("completed", "failed", "cancelled", "interrupted").contains(status)) continue;
            JsonObject itemRead = obj("threadId", id); itemRead.addProperty("itemId", entry.getKey()); itemRead.addProperty("view", "activity"); itemRead.addProperty("maxCharsPerItem", 50000);
            for (var value : T3Protocol.array(call("t3_thread_read", itemRead), "items")) history.items.put(entry.getKey(), value.getAsJsonObject().deepCopy());
        }
        for (int pageCount = 0; pageCount < 10; pageCount++) {
            for (var value : T3Protocol.array(page, "items")) {
                JsonObject item = value.getAsJsonObject(); String key = T3Protocol.string(item, "itemId");
                if (key == null) key = "position:" + item.get("position");
                history.items.put(key, item.deepCopy());
            }
            if (!page.has("nextPosition") || page.get("nextPosition").isJsonNull()) break;
            int next = page.get("nextPosition").getAsInt();
            if (history.nextPosition != null && next <= history.nextPosition) throw failure(PROTOCOL, false, "T3 MCP activity pagination did not advance.");
            history.nextPosition = next;
            if (!page.has("hasMore") || !page.get("hasMore").getAsBoolean() || pageCount == 9) break;
            args.addProperty("afterPosition", next); page = call("t3_thread_read", args);
        }
        JsonArray items = new JsonArray(), messages = new JsonArray(), activities = new JsonArray();
        for (var item : history.items.values()) items.add(item.deepCopy());
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
			JsonObject legacyDetail = viaLegacy(() -> legacy.thread(id, turns));
            if (legacyDetail.has("checkpoints")) out.add("checkpoints", legacyDetail.get("checkpoints"));
            for (var value : T3Protocol.array(legacyDetail, "activities")) {
				String kind = T3Protocol.string(value.getAsJsonObject(), "kind"); if (kind != null && kind.startsWith("approval.")) activities.add(value);
			}
		} catch (IOException | RuntimeException ignored) {}
        if ("waiting".equals(T3Protocol.string(out, "status")) && activities.isEmpty()) out.addProperty("unsupportedRequest", legacy == null ? LEGACY_REQUIRED : "Waiting on a permission request. The legacy connection is unavailable: " + legacyError);
		out.add("messages", messages); out.add("activities", activities); out.add("turnItems", items); return out;
	}
	private List<T3Api.Provider> mcpProviders() throws IOException, InterruptedException {
		List<T3Api.Provider> result = new ArrayList<>();
		for (var value : T3Protocol.array(call("orchestrator_capabilities", new JsonObject()), "providers")) {
			JsonObject p = value.getAsJsonObject();
			if (!T3Protocol.array(p, "constraints").isEmpty()) continue;
			List<T3Api.Model> models = new ArrayList<>();
			for (var m : T3Protocol.array(p, "models")) { JsonObject model = m.getAsJsonObject(); String id = T3Protocol.string(model, "id"); models.add(new T3Api.Model(id, Optional.ofNullable(T3Protocol.string(model, "label")).orElse(id))); }
			String label = Optional.ofNullable(T3Protocol.string(p, "displayName")).orElse(T3Protocol.string(p, "driverKind"));
            if (p.has("omittedModels") && !p.get("omittedModels").isJsonNull() && p.get("omittedModels").getAsInt() > 0) label += " (" + p.get("omittedModels").getAsInt() + " more models in T3 Code)";
            result.add(new T3Api.Provider(T3Protocol.string(p, "providerInstanceId"), label, false, List.copyOf(models)));
		}
		return List.copyOf(result);
	}
	private synchronized void mcpSendPrompt(JsonObject thread, String text, JsonObject model) throws IOException, InterruptedException {
		String id = thread.get("id").getAsString();
		if (model != null && !model.equals(thread.get("modelSelection"))) { JsonObject args = obj("threadId", id); args.add("modelSelection", model); call("t3_thread_configure", args); }
		JsonObject args = obj("threadId", id); args.addProperty("message", text); args.addProperty("mode", "queue"); args.addProperty("clientRequestId", UUID.randomUUID().toString()); call("t3_thread_send", args);
	}
	private String mcpStartThread(JsonObject template, String text, JsonObject model) throws IOException, InterruptedException {
		// Fetch detail because list rows intentionally omit worktree settings.
		JsonObject read = obj("threadId", template.get("id").getAsString());
		read.addProperty("limit", 1); read.addProperty("maxCharsPerItem", 1);
		JsonObject detail = call("t3_thread_read", read).getAsJsonObject("thread");
		JsonObject args = obj("message", text); args.add("projectId", template.get("projectId")); String title = text.strip().substring(0, Math.min(60, text.strip().length())).strip(); args.addProperty("title", title.isEmpty() ? "New thread" : title);
		args.add("modelSelection", model == null ? template.get("modelSelection") : model);
		args.add("runtimeMode", template.get("runtimeMode")); args.add("interactionMode", template.get("interactionMode"));
		String path = T3Protocol.string(detail, "worktreePath"); JsonObject workspace = obj("type", path == null ? "root" : "existing_worktree");
		if (path != null) workspace.addProperty("worktreePath", path); args.add("workspaceStrategy", workspace);
		args.addProperty("clientRequestId", UUID.randomUUID().toString()); return call("t3_thread_launch", args).get("threadId").getAsString();
	}
	private void mcpInterrupt(String id) throws IOException, InterruptedException { JsonObject args = obj("threadId", id); args.addProperty("clientRequestId", UUID.randomUUID().toString()); call("t3_thread_interrupt", args); }
	private void mcpAnswerQuestions(String id, String request, JsonObject answers) throws IOException, InterruptedException {
		JsonObject args = obj("threadId", id); args.addProperty("action", "respond"); args.addProperty("requestId", request); args.add("answers", answers); call("t3_pending_request", args);
	}
	public void respondToApproval(String id, String request, String decision) throws IOException, InterruptedException {
		if (legacy == null) throw new IOException(LEGACY_REQUIRED);
		viaLegacy(() -> { legacy.respondToApproval(id, request, decision); return null; });
	}
	public T3Api.Review review(String id) throws IOException, InterruptedException {
		if (legacy == null) throw new IOException(LEGACY_REQUIRED);
		return viaLegacy(() -> legacy.review(id));
	}
	static JsonObject obj(String key, String value) { JsonObject o = new JsonObject(); o.addProperty(key, value); return o; }
}
