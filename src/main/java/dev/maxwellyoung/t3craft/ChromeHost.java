package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One hidden (headless) Chrome for the whole game, started on first use with T3 Craft's own profile. It holds one tab
 * per surface (the laptop and the agent card), so each keeps its place. It closes after 10 minutes with nothing on
 * screen, and when the game quits.
 *
 * <p>The profile records which browser owns it ({@code DevToolsActivePort} from Chrome, {@code t3craft-chrome.pid} from
 * the mod). A browser that still answers on that port is adopted, never duplicated. A dead one is relaunched, and the
 * only process the mod ever ends is one the pid file names <em>and</em> that is verifiably a browser on this profile.
 */
final class ChromeHost {
	static final long IDLE_MS = TimeUnit.MINUTES.toMillis(10);
	static final String PID_FILE = "t3craft-chrome.pid";
	private static ChromeHost instance;
	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "t3craft-chrome");
		thread.setDaemon(true);
		return thread;
	});
	private static boolean hooked;

	final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	/** The browser process, or null for one adopted from an earlier launch whose pid could not be verified. */
	private final ProcessHandle process;
	private final Path profile;
	private final int port;
	private final Map<String, WebPage> pages = new ConcurrentHashMap<>();
	private volatile long lastUsed = System.currentTimeMillis();
	private volatile int visible;

	private ChromeHost(ProcessHandle process, Path profile, int port) {
		this.process = process;
		this.profile = profile;
		this.port = port;
	}

	static ScheduledExecutorService timer() { return TIMER; }

	/** Installed Chromium-family browsers, most common first. {@code T3CRAFT_CHROME} overrides discovery. */
	static List<Path> candidates() {
		List<Path> out = new ArrayList<>();
		String override = System.getenv("T3CRAFT_CHROME");
		if (override != null && !override.isBlank()) out.add(Path.of(override));
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		if (os.contains("mac")) {
			for (String app : List.of("Google Chrome.app/Contents/MacOS/Google Chrome", "Chromium.app/Contents/MacOS/Chromium",
				"Microsoft Edge.app/Contents/MacOS/Microsoft Edge", "Brave Browser.app/Contents/MacOS/Brave Browser",
				"Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary")) {
				out.add(Path.of("/Applications", app));
				out.add(Path.of(home, "Applications", app));
			}
		} else if (os.contains("win")) {
			for (String root : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
				if (root == null) continue;
				out.add(Path.of(root, "Google", "Chrome", "Application", "chrome.exe"));
				out.add(Path.of(root, "Microsoft", "Edge", "Application", "msedge.exe"));
				out.add(Path.of(root, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"));
				out.add(Path.of(root, "Chromium", "Application", "chrome.exe"));
			}
		} else {
			String path = System.getenv("PATH");
			if (path != null) for (String dir : path.split(java.io.File.pathSeparator)) {
				for (String name : List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "brave-browser")) out.add(Path.of(dir, name));
			}
		}
		return out;
	}

	static Path findBrowser() {
		for (Path candidate : candidates()) if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
		return null;
	}

	static synchronized boolean running() { return instance != null && instance.alive(); }

	/** The running browser, launching it if needed. Blocks up to about 10 seconds; call off the render thread. */
	static synchronized ChromeHost get(Path profile) throws Exception {
		if (instance != null && instance.alive()) { instance.touch(); return instance; }
		if (instance != null) instance.close();
		instance = null;
		Path browser = findBrowser();
		if (browser == null) throw new IOException("No Chrome, Chromium, Edge or Brave found");
		Files.createDirectories(profile);
		Path portFile = profile.resolve("DevToolsActivePort");
		Path pidFile = profile.resolve(PID_FILE);
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

		// An earlier launch (a previous game, or a crash before shutdown) may still be serving this profile: adopt it.
		int previous = readPort(portFile);
		if (previous > 0 && version(http, previous) != null) {
			ProcessHandle owner = ownProcess(pidFile, profile);
			instance = new ChromeHost(owner, profile, previous);
			hook();
			T3Log.LOGGER.info("Adopted the hidden browser already serving the T3 laptop profile (DevTools on loopback port {})", previous);
			return instance;
		}
		// Nothing answers. End a stale browser of our own, if the pid file still names one, so a relaunch can take the profile.
		ProcessHandle stale = ownProcess(pidFile, profile);
		if (stale != null) {
			T3Log.LOGGER.info("Ending a stale hidden browser (pid {}) left on the T3 laptop profile", stale.pid());
			kill(stale);
		}
		Files.deleteIfExists(portFile);
		Files.deleteIfExists(pidFile);

		List<String> command = new ArrayList<>(List.of(browser.toString(), "--headless=new", "--remote-debugging-port=0",
			"--remote-debugging-address=127.0.0.1", "--user-data-dir=" + profile.toAbsolutePath(), "--no-first-run",
			"--no-default-browser-check", "--mute-audio", "--force-color-profile=srgb", "--disable-background-timer-throttling",
			"--disable-renderer-backgrounding", "--disable-backgrounding-occluded-windows", "--hide-crash-restore-bubble", "--window-size=3840,2400", "about:blank"));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		// Pid plus start time: a reused pid after a reboot never matches, so it is never killed.
		Files.writeString(pidFile, process.pid() + " " + process.info().startInstant().map(i -> String.valueOf(i.toEpochMilli())).orElse("0") + "\n");
		int port = -1;
		for (int i = 0; i < 200 && process.isAlive(); i++) {
			port = readPort(portFile);
			if (port > 0) break;
			Thread.sleep(50);
		}
		if (port <= 0) {
			kill(process.toHandle());
			Files.deleteIfExists(pidFile);
			throw new IOException("Chrome did not start");
		}
		instance = new ChromeHost(process.toHandle(), profile, port);
		hook();
		T3Log.LOGGER.info("Started hidden {} for the T3 laptop (DevTools on loopback port {})", browser.getFileName(), port);
		return instance;
	}

	private static void hook() {
		if (hooked) return;
		hooked = true;
		Runtime.getRuntime().addShutdownHook(new Thread(ChromeHost::shutdown, "t3craft-chrome-exit"));
		TIMER.scheduleWithFixedDelay(ChromeHost::reapIdle, 30, 30, TimeUnit.SECONDS);
	}

	/** The port Chrome wrote to {@code DevToolsActivePort}, or -1. */
	static int readPort(Path portFile) {
		try {
			if (!Files.isRegularFile(portFile)) return -1;
			List<String> lines = Files.readAllLines(portFile);
			return lines.isEmpty() || lines.getFirst().isBlank() ? -1 : Integer.parseInt(lines.getFirst().trim());
		} catch (IOException | NumberFormatException e) {
			return -1;
		}
	}

	/** The browser-level WebSocket URL when something answers DevTools on {@code port}, else null. */
	private static String version(HttpClient http, int port) {
		try {
			HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/version"))
				.timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return null;
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			return json.has("webSocketDebuggerUrl") ? json.get("webSocketDebuggerUrl").getAsString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * The live process the pid file names, only when it is verifiably ours: same start time as recorded (when the OS
	 * reports one) and a command line that is a browser on this profile. Anything less is left alone.
	 */
	static ProcessHandle ownProcess(Path pidFile, Path profile) {
		try {
			if (!Files.isRegularFile(pidFile)) return null;
			String[] parts = Files.readString(pidFile).trim().split("\\s+");
			long pid = Long.parseLong(parts[0]);
			long started = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
			Optional<ProcessHandle> handle = ProcessHandle.of(pid);
			if (handle.isEmpty() || !handle.get().isAlive()) return null;
			ProcessHandle.Info info = handle.get().info();
			if (started > 0 && info.startInstant().isPresent() && Math.abs(info.startInstant().get().toEpochMilli() - started) > 2000) return null;
			return isBrowserOnProfile(info, profile) ? handle.get() : null;
		} catch (RuntimeException | IOException e) {
			return null;
		}
	}

	/** True when the command line names our profile directory and one of the known browser binaries. */
	static boolean isBrowserOnProfile(ProcessHandle.Info info, Path profile) {
		String wanted = "--user-data-dir=" + profile.toAbsolutePath();
		boolean onProfile = info.arguments().map(args -> List.of(args).contains(wanted)).orElse(false)
			|| info.commandLine().map(line -> line.contains(wanted)).orElse(false);
		if (!onProfile) return false;
		String command = info.command().orElse(info.commandLine().orElse("")).toLowerCase(Locale.ROOT);
		return command.contains("chrome") || command.contains("chromium") || command.contains("edge") || command.contains("brave")
			|| candidates().stream().anyMatch(c -> command.startsWith(c.toString().toLowerCase(Locale.ROOT)));
	}

	private boolean alive() {
		if (process != null) return process.isAlive();
		return version(http, port) != null; // Adopted without a verified pid: the DevTools endpoint is the only sign of life.
	}

	/** The tab for one surface ("laptop" or "card"), created on first use. */
	WebPage page(String name) throws Exception {
		WebPage existing = pages.get(name);
		if (existing != null && !existing.closed()) return existing;
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/new?about:blank"))
			.timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IOException("Chrome refused a new tab (HTTP " + response.statusCode() + ")");
		JsonObject target = JsonParser.parseString(response.body()).getAsJsonObject();
		WebPage page = WebPage.open(this, target.get("webSocketDebuggerUrl").getAsString());
		pages.put(name, page);
		return page;
	}

	void touch() { lastUsed = System.currentTimeMillis(); }
	synchronized void shown() { visible++; touch(); }
	synchronized void hidden() { visible = Math.max(0, visible - 1); touch(); }

	private static synchronized void reapIdle() {
		ChromeHost host = instance;
		if (host != null && host.visible == 0 && System.currentTimeMillis() - host.lastUsed > IDLE_MS) {
			T3Log.LOGGER.info("Closing the hidden Chrome after 10 idle minutes");
			host.close();
			instance = null;
		}
	}

	static synchronized void shutdown() {
		if (instance != null) instance.close();
		instance = null;
	}

	private void close() {
		pages.values().forEach(WebPage::close);
		pages.clear();
		// Browser.close lets Chrome write its cookie store; a kill would lose T3's session cookie and force a re-pair.
		try {
			String socket = version(http, port);
			if (socket == null) throw new IOException("DevTools gone");
			try (Cdp browser = Cdp.connect(http, socket, (method, params) -> {})) {
				browser.call("Browser.close", new JsonObject());
				if (process != null) process.onExit().get(5, TimeUnit.SECONDS);
				else Thread.sleep(500);
			}
		} catch (Exception ignored) {
			// Fall through to a plain kill of our own process, if we know it.
		}
		if (process != null) kill(process);
		try { Files.deleteIfExists(profile.resolve(PID_FILE)); } catch (IOException ignored) {}
	}

	/** Closes this browser and everything it started; never touches any other Chrome. */
	private static void kill(ProcessHandle process) {
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
		try {
			process.onExit().get(3, TimeUnit.SECONDS);
		} catch (Exception e) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
		}
	}
}
