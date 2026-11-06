package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Finds the local T3 Code that serves its own web app, and mints a one-time pairing link with T3's bundled CLI. */
final class T3Web {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	static final String CLI_MARKER = "Written by T3 Code";

	record Local(String origin, String environmentId, Path home) {
		/** T3's thread route is {@code /$environmentId/$threadId} (apps/web/src/routes/_chat.$environmentId.$threadId.tsx). */
		String threadUrl(String threadId) {
			if (threadId == null || threadId.isBlank()) return origin + "/";
			return origin + "/" + enc(environmentId) + "/" + enc(threadId);
		}
	}

	static Path home() {
		String override = System.getenv("T3CODE_HOME");
		return override != null && !override.isBlank() ? Path.of(override) : Path.of(System.getProperty("user.home"), ".t3");
	}

	/** The running local T3, or null. Reads the runtime file T3 writes, then confirms the server identity over HTTP. */
	static Local find() { return find(home()); }

	/** {@code home}: T3's data directory (normally {@code ~/.t3}); checks let a fixture stand in. */
	static Local find(Path home) {
		String origin = "http://127.0.0.1:3773";
		try {
			Path runtime = home.resolve("userdata").resolve("server-runtime.json");
			if (Files.isRegularFile(runtime)) {
				JsonObject state = JsonParser.parseString(Files.readString(runtime)).getAsJsonObject();
				if (state.has("port")) origin = "http://127.0.0.1:" + state.get("port").getAsInt();
			}
		} catch (Exception ignored) {
			// A torn or missing runtime file: try T3's default port.
		}
		try {
			HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(origin + "/.well-known/t3/environment"))
				.timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return null;
			JsonObject environment = JsonParser.parseString(response.body()).getAsJsonObject();
			String id = T3Protocol.string(environment, "environmentId");
			return id == null ? null : new Local(origin, id, home);
		} catch (Exception e) {
			return null;
		}
	}

	/** The bundled CLI, only when its launcher carries T3's own marker (never a random {@code t3} on PATH). */
	static Path cli(Path home) {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		for (String name : windows ? List.of("t3.cmd", "t3.ps1", "t3") : List.of("t3")) {
			Path candidate = home.resolve("bin").resolve(name);
			try {
				if (!Files.isRegularFile(candidate) || Files.size(candidate) > 64 * 1024) continue;
				if (Files.readString(candidate).contains(CLI_MARKER)) return candidate;
			} catch (IOException ignored) {}
		}
		return null;
	}

	/**
	 * Mints a short-lived pairing link ({@code <origin>/pair#token=…}). The credential lives only in memory and in the
	 * one navigation that redeems it; the resulting session cookie stays in T3 Craft's own Chrome profile.
	 */
	static String mintPairingUrl(Local local) throws Exception { return mintPairingUrl(local, "T3 Craft laptop", List.of(), false); }

	/**
	 * {@code label}: how the pairing shows up in T3's device list. {@code scopes}: what the link may grant (empty keeps
	 * the CLI's full default set, which the laptop's web session needs). {@code killOnTimeout}: for unattended
	 * retries, so a hung CLI never piles up; the laptop leaves a slow one to finish.
	 */
	static String mintPairingUrl(Local local, String label, List<String> scopes, boolean killOnTimeout) throws Exception {
		Path cli = cli(local.home());
		if (cli == null) throw new IOException("T3's bundled CLI was not found");
		List<String> command = new java.util.ArrayList<>(List.of(cli.toString(), "auth", "pairing", "create", "--label", label, "--ttl", "2m",
			"--base-url", local.origin(), "--json"));
		for (String scope : scopes) { command.add("--scope"); command.add(scope); }
		ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.environment().remove("VITE_DEV_SERVER_URL");
		Process process = builder.start();
		process.getOutputStream().close();
		// Drain stdout on its own daemon thread so the CLI can never block on a full pipe, however long it runs.
		CompletableFuture<byte[]> output = new CompletableFuture<>();
		Thread reader = new Thread(() -> {
			try (InputStream in = process.getInputStream()) { output.complete(in.readAllBytes()); }
			catch (IOException e) { output.completeExceptionally(e); }
		}, "t3craft-pairing-cli");
		reader.setDaemon(true);
		reader.start();
		// T3's CLI is never killed: a slow pairing is left to finish on its own while the laptop falls back to the native panel.
		if (!process.waitFor(20, TimeUnit.SECONDS)) {
			if (killOnTimeout) process.destroyForcibly();
			else process.onExit().thenAccept(done -> T3Log.LOGGER.info("t3 pairing finished late (exit {})", done.exitValue()));
			throw new IOException("t3 pairing timed out");
		}
		byte[] out;
		try { out = output.get(5, TimeUnit.SECONDS); } catch (Exception e) { throw new IOException("t3 pairing output could not be read"); }
		if (process.exitValue() != 0) throw new IOException("t3 pairing failed (exit " + process.exitValue() + ")");
		// The output carries the credential: it never reaches an exception message or the log, whatever shape it has.
		JsonObject json;
		try {
			String text = new String(out, StandardCharsets.UTF_8);
			var element = JsonParser.parseString(text.substring(Math.max(0, text.indexOf('{'))));
			if (!element.isJsonObject()) throw new IllegalStateException();
			json = element.getAsJsonObject();
		} catch (RuntimeException e) { throw new IOException("t3 pairing returned no JSON object"); }
		String url = T3Protocol.string(json, "pairUrl");
		if (url != null && url.startsWith(local.origin() + "/pair#")) return url;
		String credential = T3Protocol.string(json, "credential");
		if (credential == null || credential.isBlank()) throw new IOException("t3 pairing returned no credential");
		return local.origin() + "/pair#token=" + enc(credential);
	}

	private static final java.util.Set<String> LOOPBACK = java.util.Set.of("127.0.0.1", "localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1");

	/** True when a paired machine's address is this computer, whatever port its T3 ran on. */
	static boolean loopback(String ownerKey) {
		try { String host = URI.create(ownerKey).getHost(); return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT)); }
		catch (RuntimeException e) { return false; }
	}

	/** True when {@code ownerKey} (a paired machine's address) is the local T3 at {@code origin}, or is unknown. */
	static boolean servedBy(String origin, String ownerKey) {
		if (ownerKey == null || ownerKey.isBlank()) return true;
		try {
			URI local = URI.create(origin), owner = URI.create(ownerKey);
			int localPort = local.getPort() < 0 ? 80 : local.getPort();
			int ownerPort = owner.getPort() < 0 ? ("https".equals(owner.getScheme()) ? 443 : 80) : owner.getPort();
			String host = owner.getHost() == null ? "" : owner.getHost().toLowerCase(Locale.ROOT);
			return LOOPBACK.contains(host) && localPort == ownerPort;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Whether the laptop should deep-link: on the first open, whenever the focused thread changed since the last open,
	 * or when the player is answering a ping ({@code jump}). Otherwise the tab keeps the place the player left it at.
	 */
	static boolean deepLink(boolean openedBefore, String lastOpenedThread, String focusedThreadId, boolean jump) {
		return jump || !openedBefore || !java.util.Objects.equals(focusedThreadId, lastOpenedThread);
	}

	private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
