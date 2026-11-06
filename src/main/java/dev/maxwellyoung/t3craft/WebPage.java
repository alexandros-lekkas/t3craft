package dev.maxwellyoung.t3craft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One Chrome tab showing T3's web app: viewport, screencast (capped at 30 fps, only while on screen), a lossless
 * settle frame once the page goes still, navigation, and pairing.
 */
final class WebPage {
	static final long FRAME_INTERVAL_MS = 1000 / 30;
	static final long SETTLE_MS = 250;

	/** Newest encoded frame (JPEG while moving, PNG once still), with the viewport it was rendered for. */
	record Frame(byte[] bytes, long sequence, WebViewport viewport) {}

	private final ChromeHost host;
	private final Cdp cdp;
	private final AtomicReference<Frame> latest = new AtomicReference<>();
	private final AtomicLong sequence = new AtomicLong();
	private final AtomicBoolean capturing = new AtomicBoolean();
	private volatile WebViewport viewport;
	private volatile boolean streaming;
	/**
	 * Chrome caps screencast frames at about 7 megapixels, so a bigger destination cannot be streamed at exact size. Once
	 * a frame comes back smaller than asked, the screencast is restarted tiny and used only as a change trigger: each
	 * tiny frame requests one exact-size screenshot instead.
	 */
	private volatile boolean triggerOnly;
	private volatile long lastAck;
	private volatile boolean crashed;
	private volatile String loadedUrl;

	private WebPage(ChromeHost host, String socket) throws Exception {
		this.host = host;
		this.cdp = Cdp.connect(host.http, socket, this::event);
	}

	static WebPage open(ChromeHost host, String socket) throws Exception {
		WebPage page = new WebPage(host, socket);
		page.cdp.callSync("Page.enable", new JsonObject());
		page.cdp.callSync("Runtime.enable", new JsonObject());
		JsonObject dark = Cdp.obj("name", "prefers-color-scheme", "value", "dark");
		com.google.gson.JsonArray features = new com.google.gson.JsonArray();
		features.add(dark);
		page.cdp.send("Emulation.setEmulatedMedia", Cdp.obj("features", features));
		page.cdp.send("Emulation.setFocusEmulationEnabled", Cdp.obj("enabled", true));
		return page;
	}

	Cdp cdp() { return cdp; }
	boolean closed() { return cdp.closed || crashed; }
	/** The newest frame rendered for the current size, or null while a new size is still rendering. */
	Frame latest() {
		Frame frame = latest.get();
		return frame != null && frame.viewport() != null && frame.viewport().sameSize(viewport) ? frame : null;
	}
	WebViewport viewport() { return viewport; }
	String loadedUrl() { return loadedUrl; }
	boolean triggerOnlyForTest() { return triggerOnly; }

	private void event(String method, JsonObject params) {
		switch (method) {
			case "Page.screencastFrame" -> frame(params);
			case "Inspector.targetCrashed", "t3craft.closed" -> crashed = true;
			default -> {}
		}
	}

	private void frame(JsonObject params) {
		int session = params.get("sessionId").getAsInt();
		WebViewport current = viewport;
		byte[] bytes = Base64.getDecoder().decode(params.get("data").getAsString());
		long seq = sequence.incrementAndGet();
		int[] size = jpegSize(bytes);
		if (triggerOnly) capture(current); // The tiny frame only says "something changed".
		else if (current != null && size != null && current.matches(size[0], size[1])) latest.set(new Frame(bytes, seq, current));
		else if (current != null && streaming) {
			// Too big for the screencast: from now on it only triggers exact screenshots, at a size that costs nothing.
			triggerOnly = true;
			T3Log.LOGGER.info("The {}x{} T3 view exceeds Chrome's screencast size; switching to exact screenshots", current.physicalWidth(), current.physicalHeight());
			cdp.send("Page.stopScreencast", new JsonObject());
			cdp.send("Page.startScreencast", Cdp.trigger());
			capture(current);
		}
		host.touch();
		// Chrome sends the next frame only after an ack, so delaying the ack caps the stream at 30 fps.
		long wait = Math.max(0, lastAck + FRAME_INTERVAL_MS - System.currentTimeMillis());
		ChromeHost.timer().schedule(() -> {
			lastAck = System.currentTimeMillis();
			if (streaming) cdp.send("Page.screencastFrameAck", Cdp.obj("sessionId", session));
		}, wait, TimeUnit.MILLISECONDS);
		if (!triggerOnly) ChromeHost.timer().schedule(() -> settle(seq), SETTLE_MS, TimeUnit.MILLISECONDS);
	}

	/** The page has been still for 250 ms: replace the JPEG with a lossless PNG of the same pixels. */
	private void settle(long seq) {
		if (!streaming || sequence.get() != seq) return;
		WebViewport current = viewport;
		cdp.call("Page.captureScreenshot", Cdp.obj("format", "png", "captureBeyondViewport", false, "optimizeForSpeed", true))
			.thenAccept(result -> {
				if (sequence.get() != seq || viewport != current) return;
				byte[] png = Base64.getDecoder().decode(result.get("data").getAsString());
				latest.set(new Frame(png, sequence.incrementAndGet(), current));
			}).exceptionally(error -> null);
	}

	/** One exact-size JPEG screenshot (quality 92); at most one in flight, so it self-throttles. */
	private void capture(WebViewport current) {
		if (current == null || !streaming || !capturing.compareAndSet(false, true)) return;
		cdp.call("Page.captureScreenshot", Cdp.obj("format", "jpeg", "quality", 92, "captureBeyondViewport", false, "optimizeForSpeed", true))
			.thenAccept(result -> {
				if (viewport != current) return;
				long seq = sequence.incrementAndGet();
				latest.set(new Frame(Base64.getDecoder().decode(result.get("data").getAsString()), seq, current));
				ChromeHost.timer().schedule(() -> settle(seq), SETTLE_MS, TimeUnit.MILLISECONDS);
			})
			.whenComplete((ok, error) -> capturing.set(false));
	}

	/** Width and height from a JPEG's SOF header, or null. */
	static int[] jpegSize(byte[] b) {
		if (b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) return null;
		int i = 2;
		while (i + 9 < b.length) {
			if ((b[i] & 0xff) != 0xFF) return null;
			int marker = b[i + 1] & 0xff;
			int length = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
			if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
				return new int[] {((b[i + 7] & 0xff) << 8) | (b[i + 8] & 0xff), ((b[i + 5] & 0xff) << 8) | (b[i + 6] & 0xff)};
			}
			i += 2 + length;
		}
		return null;
	}

	/** Sizes the page to the destination rectangle; restarts the stream when the size changed. */
	synchronized void resize(WebViewport next) throws Exception {
		if (next.sameSize(viewport)) { viewport = next; return; }
		viewport = next;
		latest.set(null); // A frame of the old size must never be shown in the new rectangle.
		cdp.callSync("Emulation.setDeviceMetricsOverride", Cdp.metrics(next));
		triggerOnly = false; // The new size may fit the screencast again; the first frame decides.
		if (streaming) {
			cdp.send("Page.stopScreencast", new JsonObject());
			cdp.send("Page.startScreencast", Cdp.screencast(next));
		}
	}

	synchronized void startStreaming() {
		if (streaming || viewport == null) return;
		streaming = true;
		triggerOnly = false;
		host.shown();
		// Only the front tab renders at the emulated scale and keeps animating; make this surface the front one.
		cdp.send("Page.bringToFront", new JsonObject());
		cdp.send("Page.startScreencast", Cdp.screencast(viewport));
		// A still page sends one frame and then nothing; ask for a crisp one right away.
		ChromeHost.timer().schedule(() -> settle(sequence.get()), 120, TimeUnit.MILLISECONDS);
	}

	synchronized void stopStreaming() {
		if (!streaming) return;
		streaming = false;
		host.hidden();
		cdp.send("Page.stopScreencast", new JsonObject());
	}

	/** Navigates and waits for the load; fails when the server is unreachable. */
	void navigate(String url) throws Exception {
		JsonObject result = cdp.callSync("Page.navigate", Cdp.obj("url", url));
		if (result.has("errorText") && !result.get("errorText").getAsString().isBlank()) throw new IOException("T3 did not load: " + result.get("errorText").getAsString());
		waitFor("document.readyState === 'complete'", 10_000);
		loadedUrl = url;
	}

	/** Client-side route change inside T3's single-page app (no reload). */
	void route(String url) throws Exception {
		String path = java.net.URI.create(url).getRawPath();
		cdp.eval("(() => { if (location.pathname !== " + quote(path) + ") { history.pushState({}, '', " + quote(path)
			+ "); dispatchEvent(new PopStateEvent('popstate', {state: history.state})); } return true; })()");
		loadedUrl = url;
	}

	String pathname() throws Exception {
		JsonElement value = cdp.eval("location.pathname");
		return value == null || value.isJsonNull() ? "" : value.getAsString();
	}

	/**
	 * Opens {@code url} in T3's app, pairing this profile first if T3 shows its pairing screen. Returns once the app
	 * (not the pairing page) is on screen; throws if that does not happen within about 15 seconds.
	 */
	void openApp(T3Web.Local local, String url) throws Exception {
		if (loadedUrl != null && loadedUrl.startsWith(local.origin()) && !"/pair".equals(pathname())) {
			route(url);
			return;
		}
		navigate(url);
		if (onPairingScreen()) {
			T3Log.LOGGER.info("T3 asked this browser to pair; minting a one-time link with T3's CLI");
			String pairUrl = T3Web.mintPairingUrl(local);
			// From /pair, a /pair#token link is only a fragment change and T3 would never read it: load it fresh.
			navigate("about:blank");
			navigate(pairUrl);
			long deadline = System.currentTimeMillis() + 10_000;
			while (onPairingScreen() && System.currentTimeMillis() < deadline) Thread.sleep(200);
			if (onPairingScreen()) throw new IOException("T3 pairing did not complete");
			navigate(url);
		}
		if (onPairingScreen()) throw new IOException("T3 still asks to pair");
	}

	/** T3 sends signed-out browsers to /pair once its auth bootstrap settles. */
	private boolean onPairingScreen() throws Exception {
		long deadline = System.currentTimeMillis() + 1500;
		while (true) {
			if ("/pair".equals(pathname().replaceAll("/+$", ""))) return true;
			if (System.currentTimeMillis() > deadline) return false;
			Thread.sleep(150);
		}
	}

	private void waitFor(String expression, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			JsonElement value = cdp.eval(expression);
			if (value != null && value.isJsonPrimitive() && value.getAsBoolean()) return;
			Thread.sleep(100);
		}
		throw new IOException("Timed out waiting for T3 to load");
	}

	void close() {
		streaming = false;
		cdp.close();
	}

	static String quote(String value) { return new com.google.gson.JsonPrimitive(value).toString(); }
}
