package dev.maxwellyoung.t3craft;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Which draft the composer's text belongs to. The text is parked back under the key it was loaded for, not
 * whatever the panel targets now: after a send the target moves (new thread → focused thread), and parking the
 * emptied composer there would erase that thread's draft.
 */
final class ComposerDraft {
	private String key;
	private boolean loaded;

	/** The composer now shows {@code key}'s draft. */
	void load(String key) {
		this.key = key;
		this.loaded = true;
	}

	/** The composer's text was sent: clear the draft it came from; later typing belongs to no draft yet. */
	void sent(BiConsumer<String, String> put) {
		if (loaded) put.accept(key, "");
		key = null;
		loaded = false;
	}

	/** Park the composer's text: verbatim under its own key, or (typed after a send) appended to {@code target}'s draft. */
	void park(String target, String text, Function<String, String> get, BiConsumer<String, String> put) {
		if (loaded) {
			put.accept(key, text);
		} else if (text != null && !text.isBlank()) {
			String old = get.apply(target);
			put.accept(target, old == null || old.isBlank() ? text : old + " " + text);
		}
	}
}
