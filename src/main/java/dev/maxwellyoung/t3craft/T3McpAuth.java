package dev.maxwellyoung.t3craft;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Public OAuth client: ephemeral loopback callback, state, PKCE, no client secret. */
final class T3McpAuth {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	static String address(String value) throws IOException {
		try {
			URI uri = URI.create(value.trim());
			if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !"/mcp".equals(uri.getPath()) || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
			return uri.getScheme() + "://" + uri.getRawAuthority();
		} catch (RuntimeException e) { throw new IOException("Paste the MCP URL from T3 Settings > Environments > Copy MCP URL."); }
	}
	static boolean isMcp(String value) { try { return "/mcp".equals(URI.create(value.trim()).getPath()); } catch (RuntimeException e) { return false; } }
	static T3Config.Environment authorize(String url, Consumer<URI> browser) throws Exception {
		String base = address(url); url = base + "/mcp";
		JsonObject resource = get(base + "/.well-known/oauth-protected-resource/mcp");
		if (!url.equals(T3Protocol.string(resource, "resource"))) throw new IOException("MCP resource metadata does not match this address.");
		String issuer = resource.getAsJsonArray("authorization_servers").get(0).getAsString();
		if (!base.equals(issuer)) throw new IOException("This MCP authorization server is not supported.");
		JsonObject metadata = get(base + "/.well-known/oauth-authorization-server");
		if (!issuer.equals(T3Protocol.string(metadata, "issuer"))) throw new IOException("Invalid MCP authorization metadata.");
		String registration = endpoint(metadata, "registration_endpoint", base), authorization = endpoint(metadata, "authorization_endpoint", base), tokenEndpoint = endpoint(metadata, "token_endpoint", base);
		HttpServer listener = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		String redirect = "http://127.0.0.1:" + listener.getAddress().getPort() + "/callback";
		String state = random(), verifier = random();
		CompletableFuture<String> code = new CompletableFuture<>();
		listener.createContext("/callback", exchange -> {
			int status = 400; String message = "Invalid authorization callback. Return to Minecraft.";
			try {
				Map<String,String> query = query(exchange.getRequestURI().getRawQuery());
				if ("GET".equals(exchange.getRequestMethod()) && "/callback".equals(exchange.getRequestURI().getPath()) && state.equals(query.get("state"))
					&& (!query.containsKey("iss") || issuer.equals(query.get("iss")))) {
					if (query.containsKey("code") && !query.get("code").isBlank()) { code.complete(query.get("code")); status = 200; message = "Authorization received. Return to Minecraft."; }
					else { code.completeExceptionally(new IOException("MCP authorization was declined. Try again from Connections.")); message = "Authorization declined. Return to Minecraft."; }
				}
				byte[] bytes = message.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store");
				exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
			} finally { exchange.close(); }
		});
		try {
			listener.start();
			JsonObject registrationBody = T3McpBackend.obj("client_name", "T3 Craft (Minecraft)");
			JsonArray redirects = new JsonArray(); redirects.add(redirect); registrationBody.add("redirect_uris", redirects); registrationBody.addProperty("token_endpoint_auth_method", "none");
			String clientId = post(registration, "application/json", registrationBody.toString()).get("client_id").getAsString();
			String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
			browser.accept(URI.create(authorization + "?" + form(Map.of("response_type", "code", "client_id", clientId, "redirect_uri", redirect, "state", state,
				"code_challenge", challenge, "code_challenge_method", "S256", "resource", url, "scope", "orchestration:read orchestration:operate"))));
			String received;
			try { received = code.get(180, TimeUnit.SECONDS); } catch (TimeoutException e) { throw new IOException("MCP authorization timed out. Re-pair to open the browser again."); }
			JsonObject token = post(tokenEndpoint, "application/x-www-form-urlencoded", form(Map.of("grant_type", "authorization_code", "code", received,
				"client_id", clientId, "redirect_uri", redirect, "code_verifier", verifier, "resource", url)));
			String access = T3Protocol.string(token, "access_token");
			if (access == null || access.isBlank() || !"Bearer".equalsIgnoreCase(T3Protocol.string(token, "token_type"))) throw new IOException("Invalid MCP token response.");
			T3Config.Environment env = new T3Config.Environment(T3Api.environmentLabel(base), base, null);
			env.mcpUrl = url; env.mcpToken = access; env.mcpExpiresAt = System.currentTimeMillis() + token.get("expires_in").getAsLong() * 1000;
			return env;
		} finally { listener.stop(0); }
	}
	private static String endpoint(JsonObject metadata, String key, String base) throws IOException {
		String value = T3Protocol.string(metadata, key);
		try { URI uri = URI.create(value); if (!base.equals(uri.getScheme() + "://" + uri.getRawAuthority()) || uri.getRawFragment() != null || uri.getUserInfo() != null) throw new IllegalArgumentException(); return value; }
		catch (RuntimeException e) { throw new IOException("Invalid MCP OAuth endpoint."); }
	}
	private static JsonObject get(String url) throws IOException, InterruptedException { return send(HttpRequest.newBuilder(URI.create(url)).GET()); }
	private static JsonObject post(String url, String type, String body) throws IOException, InterruptedException { return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body))); }
	private static JsonObject send(HttpRequest.Builder request) throws IOException, InterruptedException {
		var response = HTTP.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) throw new IOException("MCP authorization request failed (HTTP " + response.statusCode() + "). Try authorizing again.");
		try { return JsonParser.parseString(response.body()).getAsJsonObject(); } catch (RuntimeException e) { throw new IOException("Invalid MCP authorization response."); }
	}
	private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
	private static String form(Map<String,String> values) { return values.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).reduce((a,b) -> a + "&" + b).orElse(""); }
	private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
	private static Map<String,String> query(String raw) {
		Map<String,String> out = new HashMap<>(); if (raw == null) return out;
		for (String pair : raw.split("&")) { String[] parts = pair.split("=", 2); if (parts.length == 2) out.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8)); } return out;
	}
}
