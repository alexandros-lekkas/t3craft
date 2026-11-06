package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * T3 Code's own web app, live inside Minecraft.
 *
 * <ul>
 *   <li><b>Laptop</b>: the whole T3 app, full screen.</li>
 *   <li><b>Agent card</b>: one agent's thread in a centred floating window over the blurred world.</li>
 * </ul>
 *
 * Every frame is exactly the destination's size in real framebuffer pixels and is drawn 1:1 with nearest filtering
 * (see {@link WebViewport}). Esc returns to the game; F-keys stay with Minecraft; everything else goes to T3.
 */
public final class T3WebScreen extends Screen {
	enum Mode { LAPTOP, CARD }

	private static final ExecutorService DECODE = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "t3craft-web-decode");
		thread.setDaemon(true);
		return thread;
	});
	private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	private static final long FIRST_FRAME_TIMEOUT_MS = 25_000;
	/** The thread the laptop was last opened for; the laptop keeps its own place until that changes or a ping says jump. */
	private static String lastLaptopThread;
	private static boolean laptopOpened;

	private final Mode mode;
	/** The thread to deep-link to, or null to leave the tab where it is. */
	private final String threadId;
	/** The focused thread when this screen was requested (recorded after a successful laptop open). */
	private final String focused;
	/** Owner of the thread ({@code T3Config.ownerKey}), or null when unknown; only the local T3 can be streamed. */
	private final String ownerKey;
	private final Runnable fallback;
	private final Identifier textureId;
	private final long openedAt = System.currentTimeMillis();
	private final AtomicReference<NativeImage> decoded = new AtomicReference<>();
	private final AtomicBoolean decoding = new AtomicBoolean();
	/** Page operations (open, resize) run in order on this screen's own thread, so a stale resize can never land last. */
	private final ExecutorService ops = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "t3craft-web");
		thread.setDaemon(true);
		return thread;
	});
	/** Guards {@link #page}, {@link #closing} and the decoded frame between the render thread and the workers. */
	private final Object lock = new Object();
	private volatile WebPage page;
	private volatile WebViewport viewport;
	private volatile String status = "Opening T3 Code…";
	private volatile boolean failed;
	private volatile boolean closing;
	private DynamicTexture texture;
	private int textureWidth, textureHeight;
	private long shownSequence = -1;
	private int buttons;

	private T3WebScreen(Mode mode, String threadId, String focused, String ownerKey, Runnable fallback) {
		super(Component.literal(mode == Mode.LAPTOP ? "T3 Code" : "T3 Code agent"));
		this.mode = mode;
		this.threadId = threadId;
		this.focused = focused;
		this.ownerKey = ownerKey;
		this.fallback = fallback;
		this.textureId = Identifier.fromNamespaceAndPath(T3CraftClient.MOD_ID, "web/" + mode.name().toLowerCase(Locale.ROOT));
	}

	/** True when a Chromium-family browser is installed; otherwise callers go straight to the native panel. */
	static boolean available() {
		// The fixture-driven self test checks the native panel; it must never reach the real local T3.
		String selfTest = System.getProperty("t3craft.selftest");
		if ((selfTest != null && !selfTest.isBlank()) || Boolean.getBoolean("t3craft.nativeOnly")) return false;
		return ChromeHost.findBrowser() != null;
	}

	/**
	 * The laptop. It deep-links to {@code focusedThreadId} on the first open, whenever the focused thread changed since
	 * the last open, or when {@code jump} is set (answering a ping). Otherwise the tab stays where the player left it.
	 */
	static T3WebScreen laptop(String focusedThreadId, String ownerKey, boolean jump, Runnable fallback) {
		boolean deepLink = T3Web.deepLink(laptopOpened, lastLaptopThread, focusedThreadId, jump);
		return new T3WebScreen(Mode.LAPTOP, deepLink ? focusedThreadId : null, focusedThreadId, ownerKey, fallback);
	}

	static T3WebScreen card(String threadId, String ownerKey, Runnable fallback) {
		return new T3WebScreen(Mode.CARD, threadId, threadId, ownerKey, fallback);
	}

	static Path profile() { return FabricLoader.getInstance().getConfigDir().resolve("t3craft").resolve("chrome"); }

	// ----- Layout -----

	/** The laptop fills the window; the card is a centred window about 60% of the screen, never wider than T3's compact layout. */
	WebViewport layout() {
		int scale = Math.max(1, (int) Math.round(minecraft.getWindow().getGuiScale()));
		// Minecraft rounds the GUI size up (2036 px / 5 = 408); use only whole GUI pixels inside the framebuffer.
		int fullW = Math.min(width, minecraft.getWindow().getWidth() / scale), fullH = Math.min(height, minecraft.getWindow().getHeight() / scale);
		if (mode == Mode.LAPTOP) return WebViewport.fit(0, 0, fullW, fullH, scale, WebViewport.LAPTOP_MIN_CSS);
		return WebViewport.card(fullW, fullH, scale);
	}

	@Override
	protected void init() {
		WebViewport next = layout();
		boolean first = viewport == null;
		viewport = next;
		if (first) ops.execute(this::start);
		else ops.execute(() -> {
			WebPage current = page;
			if (current == null || viewport != next) return; // Not loaded yet (start() sizes it), or a newer size is queued.
			try { current.resize(next); } catch (Exception e) { fail(e); }
		});
	}

	private void start() {
		try {
			T3Web.Local local = T3Web.find();
			if (local == null) throw new IllegalStateException("T3 Code is not running");
			boolean local3 = T3Web.servedBy(local.origin(), ownerKey);
			// A thread on another paired machine: the local T3 cannot show it, the native panel can.
			if (!local3 && mode == Mode.CARD) throw new IllegalStateException("thread lives on " + ownerKey + ", not the local T3");
			String link = local3 ? threadId : null;
			status = "Starting T3 Code…";
			ChromeHost host = ChromeHost.get(profile());
			WebPage tab = host.page(mode == Mode.LAPTOP ? "laptop" : "card");
			tab.resize(viewport);
			if (mode == Mode.CARD || link != null || tab.loadedUrl() == null) tab.openApp(local, local.threadUrl(link));
			if (mode == Mode.LAPTOP) { laptopOpened = true; lastLaptopThread = focused; }
			tab.resize(viewport); // The window may have changed while loading.
			synchronized (lock) {
				// removed() may have run meanwhile: then never start the stream it could not stop.
				if (closing) return;
				page = tab;
				tab.startStreaming();
			}
		} catch (Exception e) {
			fail(e);
		}
	}

	private void fail(Exception e) {
		if (failed) return;
		failed = true;
		T3Log.LOGGER.warn("T3 web view unavailable, using the native panel: {}", e.toString());
		Minecraft.getInstance().execute(() -> {
			if (Minecraft.getInstance().gui.screen() == this) fallback.run();
		});
	}

	// ----- Rendering -----

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (mode == Mode.LAPTOP) graphics.fill(0, 0, width, height, 0xff000000);
		else graphics.blurBeforeThisStratum(); // Blur only: never the dirt/menu texture.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		WebViewport view = viewport;
		if (view == null) return;
		if (!failed && page == null && System.currentTimeMillis() - openedAt > FIRST_FRAME_TIMEOUT_MS) fail(new IllegalStateException("T3 did not load in time"));
		if (!failed && page != null && page.closed()) fail(new IllegalStateException("The T3 tab closed or crashed"));
		pump();
		int x0 = view.guiX(), y0 = view.guiY(), x1 = x0 + view.guiWidth(), y1 = y0 + view.guiHeight();
		if (mode == Mode.CARD) {
			// Soft shadow and a hairline edge, like T3's own dialogs.
			for (int i = 1; i <= 4; i++) graphics.fill(x0 - i, y0 - i + 2, x1 + i, y1 + i + 2, (0x16 - i * 3) << 24);
			graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0x33ffffff);
		}
		graphics.fill(x0, y0, x1, y1, 0xff000000);
		if (texture != null) {
			// Draw in real framebuffer pixels: one frame pixel per screen pixel, never scaled.
			int scale = view.guiScale();
			graphics.enableScissor(x0, y0, x1, y1);
			graphics.pose().pushMatrix();
			graphics.pose().scale(1f / scale, 1f / scale);
			int px = view.physicalX(), py = view.physicalY();
			graphics.blit(textureId, px, py, px + textureWidth, py + textureHeight, 0f, 1f, 0f, 1f);
			graphics.pose().popMatrix();
			graphics.disableScissor();
		} else {
			String text = failed ? "Opening the T3 panel…" : status;
			graphics.centeredText(font, text, (x0 + x1) / 2, (y0 + y1) / 2 - 4, 0xff8a8a8a);
		}
	}

	/** Decodes the newest frame off the render thread and uploads the newest decoded one. */
	private void pump() {
		WebPage current = page;
		if (current != null) {
			WebPage.Frame frame = current.latest();
			if (frame != null && frame.sequence() != shownSequence && decoding.compareAndSet(false, true)) {
				shownSequence = frame.sequence();
				DECODE.execute(() -> {
					try {
						NativeImage image = decode(frame.bytes());
						synchronized (lock) {
							// A screen that closed while decoding has nobody left to draw this; free it instead of parking it.
							if (closing) { image.close(); return; }
							NativeImage old = decoded.getAndSet(image);
							if (old != null) old.close();
						}
					} catch (RuntimeException e) {
						T3Log.LOGGER.debug("Frame skipped: {}", e.getMessage());
					} finally {
						decoding.set(false);
					}
				});
			}
		}
		NativeImage image = decoded.getAndSet(null);
		if (image == null) return;
		WebViewport view = viewport;
		if (view == null || !view.matches(image.getWidth(), image.getHeight())) {
			// Mid-resize frame: never draw it scaled or misplaced; the right-sized one is on its way.
			image.close();
			return;
		}
		if (texture == null || image.getWidth() != textureWidth || image.getHeight() != textureHeight) {
			if (texture != null) minecraft.getTextureManager().release(textureId);
			texture = new DynamicTexture(() -> "T3 Code web view", image);
			textureWidth = image.getWidth();
			textureHeight = image.getHeight();
			minecraft.getTextureManager().register(textureId, texture);
		} else {
			texture.setPixels(image);
			texture.upload();
		}
	}

	/** JPEG or PNG through Minecraft's bundled STB decoder; RGBA copied straight into a NativeImage. */
	static NativeImage decode(byte[] bytes) {
		ByteBuffer input = MemoryUtil.memAlloc(bytes.length);
		ByteBuffer pixels = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			input.put(bytes).flip();
			var w = stack.mallocInt(1);
			var h = stack.mallocInt(1);
			var channels = stack.mallocInt(1);
			pixels = STBImage.stbi_load_from_memory(input, w, h, channels, 4);
			if (pixels == null) throw new IllegalStateException(STBImage.stbi_failure_reason());
			NativeImage image = new NativeImage(w.get(0), h.get(0), false);
			MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), image.getPointer(), (long) w.get(0) * h.get(0) * 4);
			return image;
		} finally {
			if (pixels != null) STBImage.stbi_image_free(pixels);
			MemoryUtil.memFree(input);
		}
	}

	// ----- Input -----

	private Cdp input() {
		WebPage current = page;
		return current == null || current.closed() ? null : current.cdp();
	}

	@Override
	public void mouseMoved(double x, double y) {
		Cdp cdp = input();
		WebViewport view = viewport;
		if (cdp != null && view != null && view.contains(x, y)) cdp.send("Input.dispatchMouseEvent", Cdp.mouse("mouseMoved", view.cssX(x), view.cssY(y), -1, buttons, 0, 0));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		WebViewport view = viewport;
		if (view == null) return true;
		if (!view.contains(event.x(), event.y())) {
			if (mode == Mode.CARD) onClose(); // Clicking the world closes the card.
			return true;
		}
		Cdp cdp = input();
		if (cdp == null) return true;
		buttons |= Cdp.buttonMask(event.button());
		cdp.send("Input.dispatchMouseEvent", Cdp.mouse("mousePressed", view.cssX(event.x()), view.cssY(event.y()), event.button(), buttons, doubleClick ? 2 : 1, event.modifiers()));
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		Cdp cdp = input();
		WebViewport view = viewport;
		if (cdp == null || view == null || (buttons & Cdp.buttonMask(event.button())) == 0) return true;
		buttons &= ~Cdp.buttonMask(event.button());
		cdp.send("Input.dispatchMouseEvent", Cdp.mouse("mouseReleased", view.cssX(event.x()), view.cssY(event.y()), event.button(), buttons, 1, event.modifiers()));
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		Cdp cdp = input();
		WebViewport view = viewport;
		if (cdp != null && view != null && buttons != 0) {
			cdp.send("Input.dispatchMouseEvent", Cdp.mouse("mouseMoved", view.cssX(event.x()), view.cssY(event.y()), event.button(), buttons, 0, event.modifiers()));
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		Cdp cdp = input();
		WebViewport view = viewport;
		if (cdp != null && view != null && view.contains(x, y)) cdp.send("Input.dispatchMouseEvent", Cdp.wheel(view.cssX(x), view.cssY(y), scrollX, scrollY, 0));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == 256) { onClose(); return true; } // Esc always returns to the game.
		if (key >= 290 && key <= 314) return false; // F1–F25 (F2 screenshot, F3 debug, F11 fullscreen) stay with Minecraft.
		Cdp cdp = input();
		if (cdp == null) return true;
		int mods = event.modifiers();
		boolean shortcut = (mods & (MAC ? 8 : 2)) != 0;
		if (shortcut && key == 86) { // Paste: headless Chrome has no system clipboard, so insert Minecraft's.
			String clip = minecraft.keyboardHandler.getClipboard();
			if (clip != null && !clip.isEmpty()) cdp.send("Input.insertText", Cdp.insertText(clip));
			return true;
		}
		Cdp.Key named = Cdp.key(key);
		if (named == null) return true;
		boolean letter = named.code().startsWith("Key") || named.code().startsWith("Digit");
		if (letter && (mods & (2 | 8)) == 0) return true; // Plain typing arrives through charTyped.
		JsonObject keyDown = Cdp.keyEvent("keyDown", named, mods, MAC);
		// Copy and cut read the selection first: a cut has already deleted it by the time the key event is processed.
		if (shortcut && (key == 67 || key == 88)) copySelectionThen(cdp, keyDown);
		else cdp.send("Input.dispatchKeyEvent", keyDown);
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		Cdp cdp = input();
		Cdp.Key named = Cdp.key(event.key());
		if (cdp != null && named != null && event.key() != 256) cdp.send("Input.dispatchKeyEvent", Cdp.keyEvent("keyUp", named, event.modifiers(), MAC));
		return event.key() < 290 || event.key() > 314;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		Cdp cdp = input();
		if (cdp != null) cdp.send("Input.insertText", Cdp.insertText(event.codepointAsString()));
		return true;
	}

	/** The page's selection, read before {@code keyDown} is dispatched, is put on the real clipboard. */
	private static void copySelectionThen(Cdp cdp, JsonObject keyDown) {
		cdp.call("Runtime.evaluate", Cdp.obj("expression", SELECTION_JS, "returnByValue", true))
			.whenComplete((result, error) -> {
				if (error == null && result != null && result.has("result")) {
					var value = result.getAsJsonObject("result").get("value");
					if (value != null && value.isJsonPrimitive() && !value.getAsString().isEmpty()) {
						String text = value.getAsString();
						Minecraft.getInstance().execute(() -> Minecraft.getInstance().keyboardHandler.setClipboard(text));
					}
				}
				cdp.send("Input.dispatchKeyEvent", keyDown); // The shortcut itself runs after the selection is safe.
			});
	}

	static final String SELECTION_JS = "(() => { const a = document.activeElement; if (a && 'selectionStart' in a && a.value != null) return a.value.substring(a.selectionStart, a.selectionEnd); return String(getSelection() || ''); })()";

	boolean hasFrame() { return texture != null; }
	/** Dev showcase only: a JSON value from the page, read off the render thread. */
	com.google.gson.JsonElement evalForShowcase(String expression) throws Exception {
		WebPage current = page;
		return current == null ? null : current.cdp().eval(expression);
	}
	WebViewport viewportForTest() { return viewport; }
	Mode mode() { return mode; }

	// ----- Lifecycle -----

	@Override public boolean isPauseScreen() { return false; }

	@Override
	public void removed() {
		synchronized (lock) {
			closing = true;
			WebPage current = page;
			if (current != null) current.stopStreaming();
			NativeImage pending = decoded.getAndSet(null);
			if (pending != null) pending.close();
		}
		ops.shutdown();
		if (texture != null) {
			minecraft.getTextureManager().release(textureId);
			texture = null;
		}
		super.removed();
	}
}
