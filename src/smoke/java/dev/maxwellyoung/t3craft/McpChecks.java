package dev.maxwellyoung.t3craft;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;

/** No real credentials, browser, Minecraft installation, or agent writes. */
public final class McpChecks {
	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static String base;
	public static void main(String[] args) throws Exception {
		base = "http://127.0.0.1:" + Integer.parseInt(args[0]); get(base + "/_qa/reset");
		T3Config.Environment env = T3McpAuth.authorize(base + "/mcp", uri -> {
			try {
				JsonObject consent = get(uri.toString());
				String callback = consent.get("redirect").getAsString();
				check(HTTP.send(HttpRequest.newBuilder(URI.create(callback + "?state=wrong&code=bad")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 400, "invalid OAuth state rejected");
				check(HTTP.send(HttpRequest.newBuilder(URI.create(callback + "?state=" + consent.get("state").getAsString() + "&code=fixture-code&iss=" + URLEncoder.encode(base, java.nio.charset.StandardCharsets.UTF_8))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "valid callback accepted");
			} catch (Exception e) { throw new RuntimeException(e); }
		});
		check(env.mcpExpiresAt > System.currentTimeMillis(), "OAuth expiry retained");
		T3Config config = new T3Config(); config.upsert(new T3Config.Environment("Legacy", base, "fixture-only")); config.upsert(env);
		check("fixture-only".equals(config.environments.getFirst().accessToken), "MCP authorization preserves legacy credential");
		Path temp = Files.createTempDirectory("t3craft-mcp-check"); Path file = temp.resolve("t3craft.json");
		try {
			check(config.save(file), "config saves");
			check(Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "tokens stored owner-only");
			check("mcp-fixture".equals(T3Config.load(file).environments.getFirst().mcpToken), "MCP credential roundtrip");
		} finally { Files.deleteIfExists(file); Files.deleteIfExists(temp); }
		env.accessToken = null;
		T3Backend api = T3Backend.forEnvironment(env);
		check(api.publicMcp() && !api.hasLegacy(), "MCP-only backend");
		JsonObject shell = api.shell(); check(shell.getAsJsonArray("threads").size() == 2, "all thread pages loaded");
		get(base + "/_qa/mcp-restart");
        check(api.shell().getAsJsonArray("threads").size() == 2, "re-initializes after T3 restart");
        var initCalls = JsonParser.parseString(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/_qa/mcp-calls")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonArray();
        check(initCalls.asList().stream().filter(c -> "initialize".equals(c.getAsJsonObject().get("name").getAsString())).count() == 2, "one recovery initialize");
        JsonObject row = shell.getAsJsonArray("threads").get(0).getAsJsonObject();
		var focus = T3State.focus(api.thread("mcp-question", 4));
		check(focus.messages().getFirst().text().equals("MCP reply"), "SSE text result decoded");
		check(focus.userInputs().size() == 1 && focus.activity().size() == 1, "questions and paginated activity loaded");
		check(api.providers(null).getFirst().models().getFirst().slug().equals("fixture-model"), "public model list");
		api.sendPrompt(row, "Fixture prompt", T3Api.modelSelection("fixture-provider", "changed-model"));
		check(api.startThread(row, "Fixture new thread", null).equals("mcp-new"), "launch retains worktree");
		api.interrupt("mcp-question");
		expectLegacy(() -> api.review("mcp-question")); expectLegacy(() -> api.respondToApproval("mcp-approval", "approval", "accept"));
		T3State state = new T3State(event -> {}); state.connect(api, "mcp-question"); state.setPanelOpen(true);
		await(() -> state.snapshot().connected() && state.snapshot().focus() != null, "MCP polling connects");
		await(() -> state.decisions().stream().anyMatch(d -> "mcp-approval".equals(d.thread().id()) && d.detail().contains("Needs legacy connection")), "approval gap visible");
		JsonObject answers = T3McpBackend.obj("format", "json");
		java.util.concurrent.atomic.AtomicReference<Exception> error = new java.util.concurrent.atomic.AtomicReference<>();
		state.respond("mcp-question", "mcp-request", null, answers, error::set);
		await(() -> state.snapshot().focus() != null && state.snapshot().focus().userInputs().isEmpty(), "question answered via state");
		check(error.get() == null, "question response succeeds");
		check(state.snapshot().focus().messages().getFirst().text().equals("Sent"), "poll sees changed message");
		state.connect(List.of(), null);
		get(base + "/_qa/mcp-hybrid"); env.accessToken = "fixture-only";
		T3Backend healthyHybrid = T3Backend.forEnvironment(env);
		check(T3State.focus(healthyHybrid.thread("mcp-approval", 4)).approvals().size() == 1, "legacy supplies approval gap");
		check(healthyHybrid.review("mcp-approval").diff().contains("greeting"), "legacy supplies checkpoint patch");
		healthyHybrid.respondToApproval("mcp-approval", "shared-request", "accept");
		check(T3State.focus(healthyHybrid.thread("mcp-approval", 4)).approvals().isEmpty(), "legacy approval response works");
		state.connect(healthyHybrid, "mcp-question");
		await(() -> state.live() && state.snapshot().focus() != null, "hybrid receives live subscriptions");
		check(state.snapshot().threads().size() == 2, "legacy snapshot cannot overwrite MCP list");
		get(base + "/_qa/protocol3");
		T3Backend hybrid = healthyHybrid;
		await(() -> !state.live() && state.snapshot().connected() && state.snapshot().focus() != null, "incompatible legacy keeps MCP online without reconnecting");
		check(!state.live(), "incompatible legacy uses polling"); try { hybrid.review("mcp-question"); throw new AssertionError("broken legacy accepted"); } catch (IOException e) { check(e.getMessage().contains("Update T3"), "original legacy error retained"); }
		state.connect(List.of(), null);
		T3Config.Environment expired = new T3Config.Environment("Expired", base, null); expired.mcpUrl = base + "/mcp"; expired.mcpToken = "private-marker"; expired.mcpExpiresAt = 1;
		expectAuth(T3Backend.forEnvironment(expired)); expired.mcpExpiresAt = 0; expectAuth(T3Backend.forEnvironment(expired));
		expired.mcpToken = "mcp-readonly"; T3Backend readonly = T3Backend.forEnvironment(expired); readonly.shell();
		try { readonly.sendPrompt(row, "Fixture prompt", null); throw new AssertionError("read-only write accepted"); } catch (IOException e) { check(e.getMessage().contains("Read only"), "read-only access explained"); }
		var calls = JsonParser.parseString(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/_qa/mcp-calls")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonArray();
		check(calls.asList().stream().anyMatch(c -> c.getAsJsonObject().get("name").getAsString().equals("t3_thread_configure")), "model switched through MCP");
		consentChecks(); get(base + "/_qa/reset"); System.out.println("MCP checks passed: OAuth/state/PKCE, private persistence, JSON/SSE, pagination, models, send/launch/stop/questions, polling and legacy/auth fallbacks.");
	}
    static T3Config.Environment authorize() throws Exception {
        return T3McpAuth.authorize(base + "/mcp", uri -> {
            try {
                JsonObject consent = get(uri.toString());
                String query = consent.entrySet().stream().filter(e -> !e.getKey().equals("redirect"))
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue().getAsString(), java.nio.charset.StandardCharsets.UTF_8)).reduce((a,b) -> a + "&" + b).orElse("");
                var response = HTTP.send(HttpRequest.newBuilder(URI.create(consent.get("redirect").getAsString() + "?" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == 200 && response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "OAuth callback HTML");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    static void consentChecks() throws Exception {
        get(base + "/_qa/reset");
        get(base + "/_qa/mcp-consent?access=read-only");
        try { authorize(); throw new AssertionError("read only paired"); } catch (T3McpException e) { check(e.getMessage().contains("Full access"), "read only pairing guidance"); }
        get(base + "/_qa/mcp-consent?access=approval-required");
        var env = authorize(); check(!env.mcpScope.contains("settings:write"), "limited scope retained");
        var api = T3Backend.forEnvironment(env); var row = api.shell().getAsJsonArray("threads").get(0).getAsJsonObject();
        try { api.sendPrompt(row, "Fixture prompt", null); throw new AssertionError("limited write accepted"); }
        catch (T3McpException e) { check(e.kind == T3McpException.Kind.DENIED && e.getMessage().contains("Full access") && !e.getMessage().contains("mcp-supervised"), "mode ceiling explained"); }
    }
	private static JsonObject get(String url) throws Exception { return JsonParser.parseString(HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject(); }
	interface Action { void run() throws Exception; }
	interface Condition { boolean get(); }
	static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
	static void await(Condition condition, String label) throws Exception { long end = System.currentTimeMillis() + 15000; while (!condition.get() && System.currentTimeMillis() < end) Thread.sleep(100); check(condition.get(), label); }
	static void expectLegacy(Action action) throws Exception { try { action.run(); throw new AssertionError("legacy gap accepted"); } catch (IOException e) { check(e.getMessage().contains("Needs legacy connection"), "legacy limitation explained"); } }
	static void expectAuth(T3Backend api) throws Exception { try { api.shell(); throw new AssertionError("expired auth accepted"); } catch (IOException e) { check(e.getMessage().contains("Re-pair") && !e.getMessage().contains("private-marker"), "re-auth without leaking credentials"); } }
}
