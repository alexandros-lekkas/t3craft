package dev.maxwellyoung.t3craft;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

/** Pure UI helpers that need no Minecraft font or screen: diff headers, table cells, ellipsizing. */
public final class UiChecks {
	public static void main(String[] args) {
		diffHeaders();
		tableCells();
		ellipsize();
		composerDrafts();
		webViewport();
		cdpMessages();
		nativeUi();
		System.out.println("PASS diff headers inside hunks, table cells with empties/escapes, code-point-safe ellipsize, composer drafts survive a send, web viewport is 1:1 and never stretched, CDP messages, "
			+ "native UI open-key letter guard, approval in-flight guard, Y/N only off the composer, laptop never on a hidden thread, new threads in the latest project, failed-send restore and question option grid");
	}

	private static void diffHeaders() {
		String patch = String.join("\n",
			"diff --git a/src/A.java b/src/A.java",
			"--- a/src/A.java",
			"+++ b/src/A.java",
			"@@ -1,3 +1,3 @@",
			" keep",
			"--- removed line that looks like a header",
			"+++ added line that looks like a header",
			" keep",
			"diff --git a/old.txt b/old.txt",
			"deleted file mode 100644",
			"--- a/old.txt",
			"+++ /dev/null",
			"@@ -1 +0,0 @@",
			"-bye",
			"");
		var files = T3Diff.files(patch);
		check(files.size() == 2, "two files, got " + files.size());
		check(files.get(0).path().equals("src/A.java"), "content lines kept the header path, got " + files.get(0).path());
		check(files.get(1).path().equals("old.txt"), "deleted file uses --- path, got " + files.get(1).path());
		var numbered = T3Diff.numbered(files.get(0));
		check(numbered.get(5).oldLine() == 2 && numbered.get(6).newLine() == 2, "header-like content lines are numbered");

		String bare = String.join("\n",
			"--- a/one.txt", "+++ b/one.txt", "@@ -1,2 +1,2 @@", "-x", "+++ y", " z",
			"--- a/two.txt", "+++ b/two.txt", "@@ -1 +1 @@", "-a", "+b");
		check(T3Diff.files(bare).getFirst().path().equals("two.txt"), "bare diff: header after an exhausted hunk still counts");
		check(T3Diff.files("").isEmpty(), "empty patch has no files");
	}

	private static void tableCells() {
		check(T3Markdown.cells("| a | b |").equals(List.of("a", "b")), "simple row");
		check(T3Markdown.cells("| a | b | |").equals(List.of("a", "b", "")), "trailing empty cell kept");
		check(T3Markdown.cells("| | x |").equals(List.of("", "x")), "leading empty cell kept");
		check(T3Markdown.cells("| a \\| b | c |").equals(List.of("a | b", "c")), "escaped pipe is literal");
		check(T3Markdown.cells("| a | b \\|").equals(List.of("a", "b |")), "escaped closing pipe stays in the cell");
	}

	private static void ellipsize() {
		// One unit per UTF-16 char: a surrogate pair is two, so a split would be visible as an odd count.
		ToIntFunction<String> width = String::length;
		check(T3Hud.ellipsize("short", 10, width).equals("short"), "fits unchanged");
		check(T3Hud.ellipsize(null, 10, width).isEmpty(), "null is empty");
		check(T3Hud.ellipsize("abcdefghij", 5, width).equals("abcd…"), "trimmed to fit with ellipsis");
		String emoji = "😀😀😀😀😀";
		for (int max = 1; max <= emoji.length(); max++) {
			String out = T3Hud.ellipsize(emoji, max, width);
			check(width.applyAsInt(out) <= max || out.equals("…"), "fits " + max);
			for (int i = 0; i < out.length(); i++) {
				check(!Character.isHighSurrogate(out.charAt(i)) || i + 1 < out.length() && Character.isLowSurrogate(out.charAt(i + 1)),
					"no lone high surrogate at width " + max);
			}
		}
		check(T3Hud.ellipsize("😀😀", 3, width).equals("😀…"), "keeps whole code points");
		check(T3Hud.ellipsize("abc", 0, width).equals("…"), "no room still ends with ellipsis");
		AtomicInteger calls = new AtomicInteger();
		String longText = "x".repeat(100_000);
		T3Hud.ellipsize(longText, 50, s -> { calls.incrementAndGet(); return s.length(); });
		check(calls.get() < 40, "binary search, not a linear scan: " + calls.get() + " width calls");
	}

	/** Mirrors T3Screen: a draft parked on thread t1, then a prompt typed and sent from + New. */
	private static void composerDrafts() {
		java.util.Map<String, String> drafts = new java.util.HashMap<>();
		java.util.function.Function<String, String> get = k -> drafts.getOrDefault(k, "");
		java.util.function.BiConsumer<String, String> put = (k, v) -> { if (v.isBlank()) drafts.remove(k); else drafts.put(k, v); };
		ComposerDraft composer = new ComposerDraft();

		composer.load("t1");
		composer.park("t1", "half-written", get, put);
		composer.load("new");
		composer.sent(put); // submit: newThread flips false, the panel now targets t1
		composer.park("t1", "", get, put); // closing the panel parks the empty composer
		check(get.apply("t1").equals("half-written"), "send from + New kept t1's parked draft, got '" + get.apply("t1") + "'");
		check(!drafts.containsKey("new"), "sent new-thread draft is cleared");

		composer.park("t1", "follow-up", get, put); // typed after the send, then closed
		check(get.apply("t1").equals("half-written follow-up"), "text typed after a send joins the target's draft, got '" + get.apply("t1") + "'");

		composer.load("t2");
		composer.park("t1", "for t2", get, put); // focus moved under the open panel
		check(get.apply("t2").equals("for t2"), "text parks under the key it was loaded for");
		composer.sent(put);
		check(!drafts.containsKey("t2"), "sending clears the draft the text came from");
	}

	private static void webViewport() {
		// The owner's setup: GUI scale 5 on a 2000x1250 framebuffer = 400x250 GUI.
		WebViewport laptop = WebViewport.fit(0, 0, 400, 250, 5, WebViewport.LAPTOP_MIN_CSS);
		check(laptop.physicalWidth() == 2000 && laptop.physicalHeight() == 1250, "laptop covers every framebuffer pixel, got " + laptop.physicalWidth() + "x" + laptop.physicalHeight());
		check(laptop.cssWidth() >= WebViewport.LAPTOP_MIN_CSS, "laptop gets T3's desktop layout, got " + laptop.cssWidth());
		check(Math.abs(laptop.cssWidth() * laptop.deviceScaleFactor() - laptop.physicalWidth()) < 1e-9
			&& Math.abs(laptop.cssHeight() * laptop.deviceScaleFactor() - laptop.physicalHeight()) < 1e-9, "CSS x DPR is exactly the destination: no stretch");
		check(laptop.matches(2000, 1250) && !laptop.matches(1999, 1250), "only an exact frame counts as 1:1");
		// Big window, small GUI scale: DPR is exactly the GUI scale (one CSS px = one GUI px).
		WebViewport wide = WebViewport.fit(0, 0, 1280, 720, 2, WebViewport.LAPTOP_MIN_CSS);
		check(wide.deviceScaleFactor() == 2.0 && wide.cssWidth() == 1280 && wide.physicalWidth() == 2560, "DPR = GUI scale when there is room");
		for (int scale = 1; scale <= 6; scale++) for (int w = 120; w <= 1000; w += 37) {
			WebViewport v = WebViewport.fit(3, 5, w, w * 5 / 8, scale, WebViewport.LAPTOP_MIN_CSS);
			check(v.physicalWidth() == w * scale && v.physicalHeight() == (w * 5 / 8) * scale, "dest px = gui x scale");
			check(Math.abs(v.cssWidth() * v.deviceScaleFactor() - v.physicalWidth()) < 1e-9, "never stretched at scale " + scale + " width " + w);
			check(v.deviceScaleFactor() >= 1.0, "never below one framebuffer pixel per CSS pixel");
		}
		// The agent card: centred, T3's compact (sidebar-less) layout.
		WebViewport card = WebViewport.fit(76, 32, 248, 185, 5, WebViewport.CARD_MIN_CSS, WebViewport.CARD_MAX_CSS);
		check(card.cssWidth() >= WebViewport.CARD_MIN_CSS && card.cssWidth() <= WebViewport.CARD_MAX_CSS, "card uses T3's compact layout, got " + card.cssWidth());
		check(card.physicalX() == 380 && card.physicalWidth() == 1240, "card position and size in real pixels");
		// The card is compact at every GUI scale: at scale 1 on a 2000-px window, 62% would be 1240 CSS px (T3's desktop layout).
		for (int scale = 1; scale <= 6; scale++) {
			WebViewport c = WebViewport.card(2000 / scale, 1250 / scale, scale);
			check(c.cssWidth() <= WebViewport.CARD_MAX_CSS, "card is compact at GUI scale " + scale + ", got " + c.cssWidth() + " CSS px");
			check(c.guiX() >= 0 && c.guiX() + c.guiWidth() <= 2000 / scale, "card stays inside the window at scale " + scale);
		}
		check(WebViewport.card(400, 250, 5).guiWidth() == 248 && WebViewport.card(400, 250, 5).guiHeight() == 185, "card size at the owner's setup");
		// Mouse: GUI → CSS, clamped inside the page.
		check(card.cssX(76) == 0 && card.cssX(76 + 124) == 124 * card.divisor() && card.cssX(10_000) == card.cssWidth() - 1, "mouse maps into CSS pixels");
		check(card.contains(76, 32) && !card.contains(75, 32) && !card.contains(76 + 248, 40), "hit test is half-open");
	}

	private static void cdpMessages() {
		WebViewport v = WebViewport.fit(0, 0, 400, 250, 5, WebViewport.LAPTOP_MIN_CSS);
		var metrics = Cdp.metrics(v);
		check(metrics.get("width").getAsInt() == v.cssWidth() && metrics.get("deviceScaleFactor").getAsDouble() == v.deviceScaleFactor() && !metrics.get("mobile").getAsBoolean(), "device metrics");
		var cast = Cdp.screencast(v);
		check(cast.get("maxWidth").getAsInt() == 2000 && cast.get("maxHeight").getAsInt() == 1250, "screencast frames are the exact physical size");
		check("jpeg".equals(cast.get("format").getAsString()) && cast.get("quality").getAsInt() >= 90, "JPEG quality >= 90");
		var message = Cdp.message(7, "Page.navigate", Cdp.obj("url", "http://127.0.0.1:3773/"));
		check(message.get("id").getAsInt() == 7 && "Page.navigate".equals(message.get("method").getAsString())
			&& "http://127.0.0.1:3773/".equals(message.getAsJsonObject("params").get("url").getAsString()), "request envelope");
		// GLFW Shift 1, Ctrl 2, Alt 4, Super 8  ->  CDP Alt 1, Ctrl 2, Meta 4, Shift 8.
		check(Cdp.modifiers(1) == 8 && Cdp.modifiers(2) == 2 && Cdp.modifiers(4) == 1 && Cdp.modifiers(8) == 4 && Cdp.modifiers(15) == 15, "modifier bits");
		var press = Cdp.mouse("mousePressed", 10.5, 20, 0, 1, 2, 0);
		check("left".equals(press.get("button").getAsString()) && press.get("clickCount").getAsInt() == 2 && press.get("buttons").getAsInt() == 1, "double click");
		check("none".equals(Cdp.mouse("mouseMoved", 1, 1, -1, 0, 0, 0).get("button").getAsString()), "hover has no button");
		check(Cdp.wheel(0, 0, 0, 1, 0).get("deltaY").getAsDouble() == -100, "wheel up scrolls the page up");
		var enter = Cdp.keyEvent("keyDown", Cdp.key(257), 0, true);
		check("Enter".equals(enter.get("key").getAsString()) && enter.get("windowsVirtualKeyCode").getAsInt() == 13 && "\r".equals(enter.get("text").getAsString()), "Enter types a newline/submits");
		var selectAll = Cdp.keyEvent("keyDown", Cdp.key(65), 8, true);
		check(selectAll.get("modifiers").getAsInt() == 4 && "selectAll".equals(selectAll.getAsJsonArray("commands").get(0).getAsString()), "Cmd+A selects all on macOS");
		check(!Cdp.keyEvent("keyDown", Cdp.key(65), 2, false).has("commands"), "no mac editing commands elsewhere");
		check(Cdp.key(290) == null && Cdp.key(256) == null, "F-keys and Esc are never forwarded");
		var shiftDown = Cdp.keyEvent("keyDown", Cdp.key(340), 0, false);
		check("Shift".equals(shiftDown.get("key").getAsString()) && "ShiftLeft".equals(shiftDown.get("code").getAsString())
			&& shiftDown.get("windowsVirtualKeyCode").getAsInt() == 16 && shiftDown.get("location").getAsInt() == 1
			&& shiftDown.get("modifiers").getAsInt() == 8 && !shiftDown.has("text"), "left Shift keydown carries its own modifier bit and location");
		check(Cdp.keyEvent("keyUp", Cdp.key(344), 1, false).get("modifiers").getAsInt() == 0 && Cdp.key(344).location() == 2, "right Shift keyup clears its bit");
		check("Control".equals(Cdp.key(341).key()) && Cdp.keyEvent("keyDown", Cdp.key(345), 0, false).get("modifiers").getAsInt() == 2, "Control keys map");
		check("Alt".equals(Cdp.key(342).key()) && Cdp.keyEvent("keyDown", Cdp.key(342), 0, false).get("modifiers").getAsInt() == 1, "Alt keys map");
		check("Meta".equals(Cdp.key(343).key()) && Cdp.key(343).windowsCode() == 91 && Cdp.key(347).windowsCode() == 93
			&& Cdp.keyEvent("keyDown", Cdp.key(343), 0, true).get("modifiers").getAsInt() == 4 && !Cdp.keyEvent("keyDown", Cdp.key(343), 0, true).has("commands"), "Meta keys map without editing commands");
		check(!Cdp.keyEvent("keyDown", Cdp.key(257), 0, false).has("location") && Cdp.keyEvent("keyDown", Cdp.key(65), 0, false).get("modifiers").getAsInt() == 0, "ordinary keys are unchanged");
		check("{\"text\":\"héllo ✓\"}".equals(Cdp.insertText("héllo ✓").toString()), "text goes through insertText");
		var local = new T3Web.Local("http://127.0.0.1:3773", "env-1", java.nio.file.Path.of("."));
		check(local.threadUrl("t-1").equals("http://127.0.0.1:3773/env-1/t-1") && local.threadUrl(null).equals("http://127.0.0.1:3773/"), "deep link route");
		try {
			var out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(37, 21, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", out);
			int[] size = WebPage.jpegSize(out.toByteArray());
			check(size != null && size[0] == 37 && size[1] == 21, "JPEG size from its header");
		} catch (java.io.IOException e) { throw new AssertionError(e); }
		check(WebPage.jpegSize(new byte[] {(byte) 0x89, 'P', 'N', 'G'}) == null, "PNG is not parsed as JPEG");
		check(local.threadUrl("thread:a%3Ab").equals("http://127.0.0.1:3773/env-1/thread%3Aa%253Ab"), "thread ids are path-encoded");
		var trigger = Cdp.trigger();
		check(trigger.get("maxWidth").getAsInt() <= 64 && trigger.get("maxHeight").getAsInt() <= 64, "the change-trigger screencast is tiny");
		// Only the local T3 can be streamed; a thread on another paired machine goes to the native panel.
		check(T3Web.servedBy("http://127.0.0.1:3773", null) && T3Web.servedBy("http://127.0.0.1:3773", "http://localhost:3773")
			&& T3Web.servedBy("http://127.0.0.1:3773", "http://127.0.0.1:3773/"), "local owners are served by the local T3");
		check(!T3Web.servedBy("http://127.0.0.1:3773", "http://10.0.0.7:3773") && !T3Web.servedBy("http://127.0.0.1:3773", "https://mac-mini.tail.ts.net")
			&& !T3Web.servedBy("http://127.0.0.1:3773", "http://127.0.0.1:3774"), "remote owners are not");
		// The laptop deep-links on the first open, when focus changed, and when answering a ping; otherwise it keeps its place.
		check(T3Web.deepLink(false, null, "t-1", false), "first open deep-links");
		check(!T3Web.deepLink(true, "t-1", "t-1", false), "reopening on the same thread keeps the laptop's place");
		check(T3Web.deepLink(true, "t-1", "t-2", false), "a new focused thread deep-links");
		check(T3Web.deepLink(true, "t-1", "t-1", true), "a ping jump deep-links even to the same thread");
	}

	/** The laptop and the card: what the review follow-ups pinned down without a running game. */
	private static void nativeUi() {
		// A letter or digit open key never closes a screen (it would eat the first character of a reply).
		check(T3CraftClient.openKeyCloses("`") && T3CraftClient.openKeyCloses("F6") && T3CraftClient.openKeyCloses("Right Shift"), "symbol and named open keys close");
		check(!T3CraftClient.openKeyCloses("T") && !T3CraftClient.openKeyCloses("7"), "letter and digit open keys never close");

		// An answered approval stays in flight until T3 drops it, never less than the grace, and never forever.
		var answered = new java.util.HashMap<String, Long>();
		answered.put("t1:r1", 1_000L);
		check(T3CraftClient.approvalInFlight(answered, "t1", List.of("r1", "r2"), 1_100), "still listed: in flight, so a double click can't reach r2");
		check(T3CraftClient.approvalInFlight(answered, "t1", List.of("r2"), 1_000 + T3CraftClient.APPROVAL_GRACE_MS - 1), "dropped but inside the grace: still in flight");
		check(!T3CraftClient.approvalInFlight(answered, "t1", List.of("r2"), 1_000 + T3CraftClient.APPROVAL_GRACE_MS + 1) && answered.isEmpty(), "dropped after the grace: the next request is live");
		answered.put("t1:r1", 1_000L);
		check(!T3CraftClient.approvalInFlight(answered, "t2", List.of(), 1_100) && answered.containsKey("t1:r1"), "another thread's answer doesn't block this one");
		check(!T3CraftClient.approvalInFlight(answered, "t1", List.of("r1"), 1_000 + T3CraftClient.APPROVAL_STALE_MS + 1), "a stuck answer frees the buttons eventually");

		approvalKeys();
		laptopThread();

		// A failed send's text goes in front of what was typed since, once.
		check(T3CraftClient.joinDraft("fix it", "").equals("fix it") && T3CraftClient.joinDraft("fix it", null).equals("fix it"), "restore into an empty box");
		check(T3CraftClient.joinDraft("fix it", "and test").equals("fix it and test"), "restore keeps what was typed since");
		check(T3CraftClient.joinDraft("fix it", "fix it").equals("fix it"), "restore never doubles the text");

		// Options: one column while they fit, two before any is hidden, never more rows than fit.
		check(T3Screen.optionGrid(3, 200, 20).equals(new T3Screen.OptionGrid(3, 1, 3)), "few options: one column");
		check(T3Screen.optionGrid(7, 89, 20).equals(new T3Screen.OptionGrid(4, 2, 7)), "GUI scale 6, seven options: two columns of four");
		check(T3Screen.optionGrid(9, 89, 20).equals(new T3Screen.OptionGrid(4, 2, 8)), "nine options in four rows: eight shown, the ninth by its key");
		check(T3Screen.optionGrid(9, 116, 20).equals(new T3Screen.OptionGrid(5, 2, 9)), "with the conversation down to one row all nine fit");
		check(T3Screen.optionGrid(4, 5, 20).rows() == 1, "a tiny window still shows one row");
		check(T3Screen.optionGrid(0, 100, 20).shown() == 0, "no options, no rows");
	}

	/** QA: after clicking Deny, typing "yes, go ahead" approved the next request through the hidden, still-focused button. */
	private static void approvalKeys() {
		var label = net.minecraft.network.chat.Component.empty();
		var approve = net.minecraft.client.gui.components.Button.builder(label, b -> {}).build();
		var deny = net.minecraft.client.gui.components.Button.builder(label, b -> {}).build();
		var send = net.minecraft.client.gui.components.Button.builder(label, b -> {}).build();
		boolean[] typing = {false};
		// A real EditBox; only its focus flag is stubbed, since setFocused needs a running client.
		var composer = new net.minecraft.client.gui.components.EditBox(null, 0, 0, 100, 18, label) {
			@Override public boolean isFocused() { return typing[0]; }
		};
		List<net.minecraft.client.gui.components.events.GuiEventListener> children = List.of(composer, send, approve, deny);
		check(T3Ui.approvalHotkey(true, T3Ui.keyFocus(null, children, approve, deny)), "nothing focused: Y answers");
		check(T3Ui.approvalHotkey(true, T3Ui.keyFocus(approve, children, approve, deny)), "a shown, Tab-focused Approve: Y answers");
		check(!T3Ui.approvalHotkey(false, T3Ui.keyFocus(null, children, approve, deny)), "no approval shown: Y is a letter");
		typing[0] = true;
		check(T3Ui.keyFocus(composer, children, approve, deny) == T3Ui.KeyFocus.TEXT_BOX && !T3Ui.approvalHotkey(true, T3Ui.KeyFocus.TEXT_BOX), "typing in the composer never approves");
		check(T3Ui.keyFocus(null, children, approve, deny) == T3Ui.KeyFocus.TEXT_BOX, "any focused text box blocks Y/N, even if the screen lost track of it");
		typing[0] = false;
		approve.visible = false; deny.visible = false;
		check(!T3Ui.approvalHotkey(true, T3Ui.keyFocus(deny, children, approve, deny)), "a hidden Deny left focused by the last click never answers the next approval");
		check(!T3Ui.approvalHotkey(true, T3Ui.keyFocus(send, children, approve, deny)), "Send focused: Y is not an answer");
		check(T3Ui.refocusText(deny, approve, deny) && T3Ui.refocusText(approve) && !T3Ui.refocusText(send, approve, deny) && !T3Ui.refocusText(null, approve, deny),
			"clicked answer buttons and hidden widgets hand the keyboard back to the composer");
	}

	/** QA: the laptop opened on a settled thread the list didn't show, and Enter would have sent into it. */
	private static void laptopThread() {
		var recent = row("recent", T3State.Status.WORKING, "2026-11-06T10:00:00Z", "Craft");
		var older = row("older", T3State.Status.DONE, "2026-11-05T10:00:00Z", "Site");
		var waiting = row("waiting", T3State.Status.NEEDS_YOU, "2026-11-01T10:00:00Z", "Old");
		check("recent".equals(FirstRun.laptopThread(List.of(recent, older), "recent", false)), "a listed choice stays selected");
		check("recent".equals(FirstRun.laptopThread(List.of(recent, older), "settled-a", false)), "a hidden saved thread gives way to the default");
		check("waiting".equals(FirstRun.laptopThread(List.of(waiting, recent, older), "settled-a", false)), "the default is the thread that needs you");
		check("recent".equals(FirstRun.laptopThread(List.of(recent, older), null, false)), "nothing chosen: the default is selected, never an empty pick");
		check("fresh".equals(FirstRun.laptopThread(List.of(recent), "fresh", true)), "a thread the laptop just started stays selected while it loads");
		check("settled-a".equals(FirstRun.laptopThread(List.of(), "settled-a", false)), "still connecting: nothing changes");
		// "+ New thread" starts in the most recently used project, not the needs-you thread sorted first.
		check(FirstRun.newThreadTemplate(List.of(waiting, older, recent)) == recent, "a new thread borrows the most recently active project");
		check(FirstRun.newThreadTemplate(List.of()) == null, "no threads: nowhere to start one");
	}

	private static T3State.ThreadRow row(String id, T3State.Status status, String updatedAt, String project) {
		var raw = new com.google.gson.JsonObject();
		raw.addProperty("updatedAt", updatedAt);
		return new T3State.ThreadRow(id, "Thread " + id, project, "Studio", "http://127.0.0.1:1", status, null, null, raw);
	}

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
