package dev.maxwellyoung.t3craft;

import com.sun.net.httpserver.Headers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Security boundary and recovery checks against isolated fixtures, without agent commands. */
public final class ReliabilityChecks {
	public static void main(String[] args) throws Exception {
		T3State unpaired = new T3State(event -> {});
		check(!unpaired.online(null) && unpaired.connectionError(null) == null, "unpaired panel has no thread owner");
		for (String host : new String[] {"127.0.0.1:25590", "localhost:25590", "[::1]:25590"}) {
			Headers headers = new Headers(); headers.set("Host", host);
			check(McpRequestPolicy.allows(headers, 25590), "native loopback host");
			for (String origin : new String[] {"http://localhost:3000", "http://localhost.attacker.example", "http://127.0.0.1.attacker.example", "null", ""}) {
				headers.set("Origin", origin);
				check(!McpRequestPolicy.allows(headers, 25590), "all browser origins refused");
			}
		}
		for (String host : new String[] {"localhost.attacker.example:25590", "127.0.0.1.attacker.example:25590", "localhost:25591", "localhost", "localhost:25590/path", "user@localhost:25590", "localhost:25590#fragment", "localhost:25590?query", "localhost.:25590", "127.1:25590"}) {
			Headers headers = new Headers(); headers.set("Host", host);
			check(!McpRequestPolicy.allows(headers, 25590), "noncanonical Host refused");
		}
		Headers missing = new Headers(); check(!McpRequestPolicy.allows(missing, 25590), "missing Host refused");
		missing.add("Host", "localhost:25590"); missing.add("Host", "attacker.example:25590");
		check(!McpRequestPolicy.allows(missing, 25590), "duplicate Host refused");
		checkJsonRpc();
		checkPortFallback();
		String base = "http://127.0.0.1:" + args[0];
		get(base + "/_qa/reset");
		try {
			T3Api api = new T3Api(base, "fixture-only");
			api.thread("A-done-0", 4); // First request must negotiate before choosing the thread endpoint.
			get(base + "/_qa/auth-failed");
			try { api.shell(); throw new IllegalStateException("expected auth failure"); }
			catch (T3Api.AuthException e) { check(e.getMessage().contains("Settings → Environments"), "auth recovery instruction"); }
			get(base + "/_qa/reset"); get(base + "/_qa/protocol3");
			api.forgetProtocol();
			try { api.shell(); throw new IllegalStateException("expected unsupported protocol"); }
			catch (T3Api.UnsupportedVersionException e) { check(e.getMessage().contains("Update T3 Craft"), "future-protocol recovery instruction"); }
		} finally { get(base + "/_qa/reset"); }
		System.out.println("PASS MCP native-client boundary, exact Host validation, JSON-RPC error conformance, port fallback and port file, auth recovery and unsupported protocol rejection");
	}
	/** Malformed requests get JSON-RPC errors with the spec's codes, never a bare HTTP status or silence. */
	private static void checkJsonRpc() {
		var calls = new java.util.ArrayList<String>();
		var rpc = new McpJsonRpc(() -> { var tools = new com.google.gson.JsonArray(); tools.add(new com.google.gson.JsonObject()); return tools; },
			(name, arguments) -> { calls.add(name + ":" + arguments); var result = new com.google.gson.JsonObject(); result.addProperty("isError", false); return result; });
		java.util.function.Function<String, McpJsonRpc.Response> post = body -> rpc.handle(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
		java.util.function.ToIntFunction<McpJsonRpc.Response> code = r -> r.body().getAsJsonObject().getAsJsonObject("error").get("code").getAsInt();
		var parse = post.apply("{bad");
		check(parse.status() == 400 && code.applyAsInt(parse) == -32700 && parse.body().getAsJsonObject().get("id").isJsonNull(), "parse error -32700 with null id");
		check(rpc.handle("x".getBytes(), true).status() == 413 && code.applyAsInt(rpc.handle("x".getBytes(), true)) == -32700, "oversized body is a 413 with a parse error body");
		for (String body : new String[] {"null", "42", "\"text\"", "[]", "{\"id\":1}", "{\"id\":{},\"method\":\"ping\"}", "{\"id\":1,\"method\":7}",
			"{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", "{\"id\":1,\"method\":\"ping\",\"params\":\"nope\"}", "{}"}) {
			var response = post.apply(body);
			check(response.status() == 400 && code.applyAsInt(response) == -32600, "invalid request -32600 for " + body);
		}
		check(post.apply("{\"id\":1,\"method\":\"ping\",\"params\":\"nope\"}").body().getAsJsonObject().get("id").getAsInt() == 1, "invalid request keeps the caller's id");
		var unknown = post.apply("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"nope\"}");
		check(unknown.status() == 200 && code.applyAsInt(unknown) == -32601 && "a".equals(unknown.body().getAsJsonObject().get("id").getAsString()), "method not found -32601");
		for (String params : new String[] {"{}", "{\"name\":5}", "{\"name\":\"\"}", "{\"name\":\"minecraft_say\",\"arguments\":[]}"}) {
			var response = post.apply("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":" + params + "}");
			check(response.status() == 200 && code.applyAsInt(response) == -32602, "invalid params -32602 for " + params);
		}
		check(calls.isEmpty(), "no tool ran for malformed calls");
		var ok = post.apply("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"minecraft_say\",\"arguments\":{\"message\":\"hi\"}}}");
		check(ok.status() == 200 && ok.body().getAsJsonObject().has("result") && calls.equals(java.util.List.of("minecraft_say:{\"message\":\"hi\"}")), "well-formed call reaches the tool");
		check(post.apply("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").status() == 202, "notification is accepted silently");
		var init = post.apply("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
		check(init.status() == 200 && "2025-03-26".equals(init.body().getAsJsonObject().getAsJsonObject("result").get("protocolVersion").getAsString()), "initialize echoes the protocol");
		var batch = post.apply("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},5,{\"jsonrpc\":\"2.0\",\"method\":\"note\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}]");
		var replies = batch.body().getAsJsonArray();
		check(batch.status() == 200 && replies.size() == 3 && replies.get(1).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt() == -32600
			&& replies.get(2).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").size() == 1, "batch answers requests, flags junk, skips notifications");
	}

	/** A busy port moves the server to the next free one; the bound port is what the policy and log use. */
	private static void checkPortFallback() throws Exception {
		// Ten ports above the taken one must exist, so an ephemeral port at the very top of the range is passed over.
		var first = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
		while (first.getLocalPort() > 65535 - T3Mcp.PORT_ATTEMPTS) { first.close(); first = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()); }
		try (var taken = first) {
			int busy = taken.getLocalPort();
			var server = T3Mcp.bind(busy, T3Mcp.PORT_ATTEMPTS);
			try {
				check(server.getAddress().getPort() != busy && server.getAddress().getPort() > busy && server.getAddress().getPort() < busy + T3Mcp.PORT_ATTEMPTS, "fallback picks the next free port");
				check("127.0.0.1".equals(server.getAddress().getAddress().getHostAddress()), "MCP binds the documented IPv4 loopback");
				Headers headers = new Headers(); headers.set("Host", "127.0.0.1:" + server.getAddress().getPort());
				check(McpRequestPolicy.allows(headers, server.getAddress().getPort()) && !McpRequestPolicy.allows(headers, busy), "policy follows the bound port");
			} finally { server.stop(0); }
		}
		var dir = java.nio.file.Files.createTempDirectory("t3craft-mcp-port-");
		var file = dir.resolve("t3craft-mcp.json");
		try {
			check(T3Mcp.writePortFile(file, 25593, 42), "port file written");
			var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
			check(json.get("url").getAsString().equals("http://127.0.0.1:25593/mcp") && json.get("port").getAsInt() == 25593 && json.get("pid").getAsLong() == 42, "port file carries the bound URL, port and pid");
			T3Mcp.clearPortFile(file, 7);
			check(java.nio.file.Files.exists(file), "another game's port file is left alone on quit");
			T3Mcp.clearPortFile(file, 42);
			check(!java.nio.file.Files.exists(file), "the game's own port file is removed on quit");
		} finally {
			java.nio.file.Files.deleteIfExists(file);
			try (var left = java.nio.file.Files.list(dir)) { check(left.findAny().isEmpty(), "no temporary port file left behind"); }
			java.nio.file.Files.deleteIfExists(dir);
		}
		var config = new T3Config(); config.mcpPort = 0;
		check(T3Mcp.preferredPort(config) == T3Config.DEFAULT_MCP_PORT, "invalid configured port falls back to the default");
		config.mcpPort = 25601;
		check(T3Mcp.preferredPort(config) == 25601, "configured port is preferred");
		System.setProperty("t3craft.mcpPort", "25777");
		try { check(T3Mcp.preferredPort(config) == 25777, "system property overrides the config for test runs"); }
		finally { System.clearProperty("t3craft.mcpPort"); }
	}

	private static void get(String url) throws Exception {
		HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding());
	}
	private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
