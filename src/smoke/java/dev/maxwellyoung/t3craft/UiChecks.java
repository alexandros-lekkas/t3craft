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
		System.out.println("PASS diff headers inside hunks, table cells with empties/escapes, code-point-safe ellipsize");
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

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
