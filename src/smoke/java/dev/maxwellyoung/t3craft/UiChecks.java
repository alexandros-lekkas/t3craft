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
		System.out.println("PASS diff headers inside hunks, table cells with empties/escapes, code-point-safe ellipsize, composer drafts survive a send");
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

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
