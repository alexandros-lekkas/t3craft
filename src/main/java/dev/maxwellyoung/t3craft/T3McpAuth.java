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
				|| uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !("/mcp".equals(uri.getPath()) || "/mcp/".equals(uri.getPath())) || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
			return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() < 0 || uri.getPort() == ("https".equals(uri.getScheme()) ? 443 : 80) ? "" : ":" + uri.getPort());
		} catch (RuntimeException e) { throw new IOException("Paste the MCP URL from T3 Settings > Environments > Copy MCP URL."); }
	}
	static boolean isMcp(String value) { try { return Set.of("/mcp", "/mcp/").contains(URI.create(value.trim()).getPath()); } catch (RuntimeException e) { return false; } }
	static final class Pending {
  volatile URI consent;
  private final CompletableFuture<String> code = new CompletableFuture<>();
  void cancel() { code.completeExceptionally(new T3McpException(T3McpException.Kind.AUTH, null, true, "Authorization cancelled.")); }
 }
 static boolean sameResource(String a, String b) {
  try { URI x = URI.create(a), y = URI.create(b); return Objects.equals(x.getScheme(), y.getScheme()) && x.getHost().equalsIgnoreCase(y.getHost())
   && port(x) == port(y) && x.getPath().replaceAll("/$", "").equals(y.getPath().replaceAll("/$", "")); } catch (RuntimeException e) { return false; }
 }
 private static int port(URI u) { return u.getPort() >= 0 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80; }
 static T3Config.Environment authorize(String url, Consumer<URI> browser) throws Exception { return authorize(url, browser, new Pending()); }
 static T3Config.Environment authorize(String url, Consumer<URI> browser, Pending pending) throws Exception {
		String base = address(url); url = base + "/mcp";
		JsonObject resource = get(base + "/.well-known/oauth-protected-resource/mcp");
		if (!sameResource(url, T3Protocol.string(resource, "resource"))) throw new IOException("MCP resource metadata does not match this address.");
		String issuer = resource.getAsJsonArray("authorization_servers").get(0).getAsString();
		if (!sameResource(base, issuer)) throw new IOException("This MCP authorization server is not supported.");
		JsonObject metadata = get(base + "/.well-known/oauth-authorization-server");
		if (!sameResource(issuer, T3Protocol.string(metadata, "issuer"))) throw new IOException("Invalid MCP authorization metadata.");
		String registration = endpoint(metadata, "registration_endpoint", base), authorization = endpoint(metadata, "authorization_endpoint", base), tokenEndpoint = endpoint(metadata, "token_endpoint", base);
		HttpServer listener = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		String redirect = "http://127.0.0.1:" + listener.getAddress().getPort() + "/callback";
		String state = random(), verifier = random();
		CompletableFuture<String> code = pending.code;
		listener.createContext("/callback", exchange -> {
			int status = 400; String message = "Invalid authorization callback. Return to Minecraft.";
			try {
				Map<String,String> query = query(exchange.getRequestURI().getRawQuery());
				if ("GET".equals(exchange.getRequestMethod()) && "/callback".equals(exchange.getRequestURI().getPath()) && state.equals(query.get("state"))
					&& (!query.containsKey("iss") || sameResource(issuer, query.get("iss")))) {
                    if (query.containsKey("error")) {
                        String error = query.get("error"), description = query.get("error_description");
                        if (description != null) description = description.substring(0, Math.min(200, description.length()));
                        code.completeExceptionally(new T3McpException(T3McpException.Kind.DENIED, error, true, "access_denied".equals(error)
                            ? "Authorization was denied in the browser. Choose Pair machine to try again, then choose Full access and Approve."
                            : "T3 authorization failed: " + error + (description == null ? "" : " (" + description + ")")));
                        status = 200; message = "authorization denied";
                    } else if (query.containsKey("code") && !query.get("code").isBlank()) { code.complete(query.get("code")); status = 200; message = "authorized"; }
				}
				message = "<!doctype html><meta charset=utf-8><title>T3 Craft</title><p>T3 Craft: " + message + ". You can close this tab and return to Minecraft.</p>";
				byte[] bytes = message.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store");
				exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
			} finally { exchange.close(); }
		});
		try {
			listener.start();
			JsonObject registrationBody = T3McpBackend.obj("client_name", "T3 Craft (Minecraft)");
			JsonArray redirects = new JsonArray(); redirects.add(redirect); registrationBody.add("redirect_uris", redirects); registrationBody.addProperty("token_endpoint_auth_method", "none");
			String clientId = post(registration, "application/json", registrationBody.toString()).get("client_id").getAsString();
			String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
			pending.consent = URI.create(authorization + "?" + form(Map.of("response_type", "code", "client_id", clientId, "redirect_uri", redirect, "state", state,
				"code_challenge", challenge, "code_challenge_method", "S256", "resource", url, "scope", "orchestration:read orchestration:operate")));
            browser.accept(pending.consent);
			String received;
			try { received = code.get(180, TimeUnit.SECONDS); }
            catch (ExecutionException e) { if (e.getCause() instanceof IOException io) throw io; throw new IOException("MCP authorization failed."); }
            catch (TimeoutException e) { throw new T3McpException(T3McpException.Kind.AUTH, null, true, "MCP authorization timed out after 3 minutes. Choose Pair machine to open the browser again."); }
			JsonObject token = post(tokenEndpoint, "application/x-www-form-urlencoded", form(Map.of("grant_type", "authorization_code", "code", received,
				"client_id", clientId, "redirect_uri", redirect, "code_verifier", verifier, "resource", url)));
			String access = T3Protocol.string(token, "access_token");
			if (access == null || access.isBlank() || !"Bearer".equalsIgnoreCase(T3Protocol.string(token, "token_type"))) throw new IOException("Invalid MCP token response.");
			T3Config.Environment env = new T3Config.Environment(T3Api.environmentLabel(base), base, null);
			env.mcpUrl = url; env.mcpToken = access; env.mcpExpiresAt = token.has("expires_in") ? System.currentTimeMillis() + token.get("expires_in").getAsLong() * 1000 : 0;
            env.mcpScope = T3Protocol.string(token, "scope");
            if (env.mcpScope != null && !Arrays.asList(env.mcpScope.split(" ")).contains("orchestration:operate"))
                throw new T3McpException(T3McpException.Kind.DENIED, "capability_denied", true, "T3 granted Read only access, so T3 Craft could not be paired. Choose Pair machine again and pick Full access on T3's consent page.");
			return env;
		} finally { listener.stop(0); }
	}
	private static String endpoint(JsonObject metadata, String key, String base) throws IOException {
		String value = T3Protocol.string(metadata, key);
		try { URI uri = URI.create(value); if (!sameResource(base, uri.getScheme() + "://" + uri.getRawAuthority()) || uri.getRawFragment() != null || uri.getUserInfo() != null) throw new IllegalArgumentException(); return value; }
		catch (RuntimeException e) { throw new IOException("Invalid MCP OAuth endpoint."); }
	}
	private static JsonObject get(String url) throws IOException, InterruptedException { return send(HttpRequest.newBuilder(URI.create(url)).GET()); }
	private static JsonObject post(String url, String type, String body) throws IOException, InterruptedException { return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body))); }
	private static JsonObject send(HttpRequest.Builder request) throws IOException, InterruptedException {
		var response = HTTP.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
            String description = "Try authorizing again.";
            try { JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                for (String key : List.of("error_description", "message", "error")) { String value = T3Protocol.string(body, key); if (value != null) { description = value.substring(0, Math.min(200, value.length())); break; } }
            } catch (RuntimeException ignored) {}
            throw new T3McpException(T3McpException.Kind.AUTH, null, true, "MCP authorization request failed (HTTP " + response.statusCode() + "): " + description);
        }
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
