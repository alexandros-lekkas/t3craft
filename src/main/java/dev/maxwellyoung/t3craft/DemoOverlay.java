package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of the dev-only launch video ({@link DemoRecorder}), drawn over the HUD (and over an open screen) in the
 * game's own font: captions whose words pop in one after another, a keycap that shows a key press, the mouse pointer
 * a hidden window does not have, and the title card. No grey box: white type with a thick dark outline. Sizes are in
 * pixels of a 1080-line frame, so the text keeps its size whatever the GUI scale; times are seconds on the recording
 * clock.
 */
final class DemoOverlay {
	private static final int INK = 0xFF10141A, WHITE = 0xFFFFFFFF, YELLOW = 0xFFFFE23B;
	private static final int[][] AROUND = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
	/** How long one word takes to pop in, and how far apart words start. */
	private static final double POP = 0.35, STAGGER = 0.15;
	/** The system arrow, one character a cell: X its body, o its outline. */
	private static final String[] ARROW = {
		"o", "oo", "oXo", "oXXo", "oXXXo", "oXXXXo", "oXXXXXo", "oXXXXXXo", "oXXXXXXXo", "oXXXXXXXXo", "oXXXXXXXXXo", "oXXXXXXooooo",
		"oXXXoXXo", "oXXo oXXo", "oXo  oXXo", "oo    oXXo", "o     oXXo", "       oXXo", "       ooo"};

	private record Word(String text, int line, int rgb, boolean pulse, double at) {}
	private record Keycap(String key, double at, double pressAt, float x, float y, float size) {}

	private double now;
	private List<Word> words = List.of();
	private int lines;
	private float bottom = 860;
	private boolean shade;
	private Keycap keycap;
	private double titleAt = Double.NaN;
	private String title = "";
	private String[] under = {};
	private float pointerX = Float.NaN, pointerY;
	private net.minecraft.client.gui.components.toasts.Toast toast;
	private double toastAt, toastGrowAt = Double.NaN, toastBackAt = Double.NaN;

	void time(double seconds) { now = seconds; }

	/**
	 * Shows a caption from {@code at}: lines of words, each popping in after the one before, a line starting
	 * {@code lineGap} seconds after the last word of the line above. A word starting with {@code *} is the key word
	 * (yellow, pulsing); {@code !}, {@code ~} and {@code +} take the status colours (needs you, working, done).
	 * {@code bottom} is the bottom edge of the last line. It stays until {@link #clear}.
	 */
	void caption(double at, double lineGap, float bottom, boolean shade, String... text) {
		List<Word> out = new ArrayList<>();
		double when = at;
		for (int line = 0; line < text.length; line++) {
			for (String word : text[line].split(" ")) {
				int rgb = WHITE;
				boolean pulse = false;
				if (word.startsWith("*")) { rgb = YELLOW; pulse = true; }
				else if (word.startsWith("!")) rgb = T3Hud.color(T3State.Status.NEEDS_YOU);
				else if (word.startsWith("~")) rgb = T3Hud.color(T3State.Status.WORKING);
				else if (word.startsWith("+")) rgb = T3Hud.color(T3State.Status.DONE);
				if (rgb != WHITE) word = word.substring(1);
				out.add(new Word(word, line, rgb, pulse, when));
				when += STAGGER;
			}
			when += lineGap;
		}
		words = out;
		lines = text.length;
		this.bottom = bottom;
		this.shade = shade;
	}

	/** A caption on the one baseline every shot shares. */
	void caption(double at, String... text) { caption(at, STAGGER, 860, true, text); }

	/** Has the caption's key word (the yellow one) pop in at {@code at}, whenever the rest arrived: a caption that is up on the first frame still moves. */
	void keyWord(double at) {
		List<Word> out = new ArrayList<>();
		for (Word w : words) out.add(w.pulse() ? new Word(w.text(), w.line(), w.rgb(), true, at) : w);
		words = out;
	}

	/**
	 * Takes over a toast from the game: it slides in at the top right as the game's own does, {@link #toastGrow grows}
	 * to the middle of the frame so it can be read on a phone, and {@link #toastBack goes back} to its corner. One
	 * object throughout; the gameplay dims a little behind it while it is large.
	 */
	void toast(net.minecraft.client.gui.components.toasts.Toast toast, double at) {
		this.toast = toast;
		toastAt = at;
		toastGrowAt = toastBackAt = Double.NaN;
	}

	void toastGrow(double at) { toastGrowAt = at; }

	void toastBack(double at) { toastBackAt = at; }

	/** When the last word of the caption on screen has finished popping in. */
	double captionShown() { return words.isEmpty() ? now : words.getLast().at() + POP; }

	void clearCaption() { words = List.of(); }

	/** Pops a keyboard key centred on ({@code x}, {@code y}) of a 1920 x 1080 frame, {@code size} pixels a font pixel; it goes down at {@code pressAt}. */
	void keycap(String key, double at, double pressAt, float x, float y, float size) { keycap = new Keycap(key, at, pressAt, x, y, size); }

	/** The mouse pointer, its tip at ({@code x}, {@code y}) of a 1920 x 1080 frame; NaN hides it. */
	void pointer(float x, float y) {
		pointerX = x;
		pointerY = y;
	}

	/** The title card: the name dropping in letter by letter, three status dots, then the lines under it. */
	void title(double at, String title, String... under) {
		titleAt = at;
		this.title = title;
		this.under = under;
	}

	/** Everything leaves with the cut. */
	void clear() {
		words = List.of();
		keycap = null;
		titleAt = Double.NaN;
		pointerX = Float.NaN;
		toast = null;
	}

	void clearKeycap() { keycap = null; }

	// ---- Drawing ----

	void draw(GuiGraphicsExtractor graphics) {
		if (words.isEmpty() && keycap == null && Double.isNaN(titleAt) && Float.isNaN(pointerX) && toast == null) return;
		Font font = Minecraft.getInstance().font;
		// One unit is one pixel of a 1080-line frame.
		float unit = graphics.guiHeight() / 1080f;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(unit, unit);
		if (toast != null) toast(graphics, font, unit);
		if (!words.isEmpty()) caption(graphics, font);
		if (keycap != null) keycap(graphics, font);
		if (!Double.isNaN(titleAt)) title(graphics, font);
		if (!Float.isNaN(pointerX)) pointer(graphics);
		pose.popMatrix();
	}

	/** 0 → 1.18 → 1 over {@link #POP}: the pop of a word, a dot or a key. */
	private static float pop(double age) {
		if (age <= 0) return 0;
		double k = age / POP;
		if (k >= 1) return 1;
		return (float) (k < 0.55 ? 1.18 * ease(k / 0.55) : 1.18 - 0.18 * ease((k - 0.55) / 0.45));
	}

	private static double ease(double k) { return 1 - (1 - k) * (1 - k); }

	private void caption(GuiGraphicsExtractor graphics, Font font) {
		// Capitals 63 px tall: the font's are 7.
		float size = 9f;
		if (shade && now >= words.getFirst().at() - 0.3) {
			int alpha = (int) (0x73 * Mth.clamp((now - words.getFirst().at() + 0.3) / 0.3, 0, 1));
			graphics.fillGradient(0, 640, 1920, 1080, 0, alpha << 24);
		}
		float lineHeight = 11 * size, top = bottom - (lines - 1) * lineHeight - 8 * size;
		for (int line = 0; line < lines; line++) {
			float width = 0;
			for (Word w : words) if (w.line() == line) width += (font.width(w.text()) + (width > 0 ? 4 : 0)) * size;
			float x = 960 - width / 2, y = top + line * lineHeight;
			for (Word w : words) {
				if (w.line() != line) continue;
				float wide = font.width(w.text()) * size, scale = pop(now - w.at());
				if (scale > 0) {
					// The key word breathes like the splash on the title screen.
					if (w.pulse() && now - w.at() > POP) scale *= 1 + 0.045f * (float) Math.abs(Math.sin((now - w.at() - POP) * Math.PI * 1.6));
					text(graphics, font, w.text(), x + wide / 2, y + 4 * size, size * scale, w.rgb(), 1);
				}
				x += wide + 4 * size;
			}
		}
	}

	/** Outlined text centred on ({@code x}, {@code y}), {@code size} pixels per font pixel. */
	private static void text(GuiGraphicsExtractor graphics, Font font, String text, float x, float y, float size, int rgb, float alpha) {
		if (alpha < 0.03f) return;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(size, size);
		int left = -font.width(text) / 2, a = (int) (alpha * 255) << 24;
		for (int[] o : AROUND) graphics.text(font, text, left + o[0], -4 + o[1], a | (INK & 0xFFFFFF), false);
		// A second ring below gives the letters weight, like a drop shadow.
		graphics.text(font, text, left, -4 + 2, a | (INK & 0xFFFFFF), false);
		graphics.text(font, text, left, -4, a | (rgb & 0xFFFFFF), false);
		pose.popMatrix();
	}

	private void toast(GuiGraphicsExtractor graphics, Font font, float unit) {
		double age = now - toastAt;
		if (age < 0) return;
		float in = (float) ease(Mth.clamp(age / 0.5, 0, 1)), big = Double.isNaN(toastGrowAt) ? 0 : smooth((now - toastGrowAt) / 0.32);
		if (!Double.isNaN(toastBackAt)) big *= 1 - smooth((now - toastBackAt) / 0.3);
		// The game's own size is one GUI pixel to 1 / unit frame pixels; large, it is 1.8 times that, centred near the top.
		float scale = (1 + 0.8f * big) / unit, wide = toast.width() * scale, x = Mth.lerp(big, 1920 - toast.width() / unit * in, 960 - wide / 2), y = Mth.lerp(big, 0, 84);
		if (big > 0) graphics.fill(0, 0, 1920, 1080, (int) (0x4D * big) << 24);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		toast.extractRenderState(graphics, font, (long) (age * 1000));
		pose.popMatrix();
	}

	private static float smooth(double k) {
		k = Mth.clamp(k, 0, 1);
		return (float) (k * k * (3 - 2 * k));
	}

	private void keycap(GuiGraphicsExtractor graphics, Font font) {
		Keycap k = keycap;
		float scale = pop(now - k.at()), alpha = (float) Mth.clamp(1 - (now - k.pressAt() - 0.7) / 0.25, 0, 1);
		if (scale <= 0 || alpha <= 0) return;
		boolean down = now >= k.pressAt() && now < k.pressAt() + 0.16;
		// Pressing it is a second, smaller pop.
		if (now >= k.pressAt()) scale *= 1 + 0.1f * (float) Math.max(0, 1 - (now - k.pressAt()) / 0.22);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(k.x(), k.y());
		pose.scale(scale * k.size(), scale * k.size());
		int a = (int) (alpha * 255) << 24, drop = down ? 1 : 0, half = Math.max(8, font.width(k.key()) / 2 + 5);
		// A key 17 font pixels tall, as wide as its label needs: dark rim, a lip below the face that the press pushes in.
		graphics.fill(-half, -8 + drop, half, 9, a | (INK & 0xFFFFFF));
		graphics.fill(-half + 1, -7 + drop, half - 1, 8, a | 0x8E96A3);
		graphics.fill(-half + 1, -7 + drop, half - 1, 5 + drop, a | (down ? 0xFFE23B : 0xF2F4F7));
		graphics.text(font, k.key(), -font.width(k.key()) / 2 + 1, -5 + drop, a | (INK & 0xFFFFFF), false);
		pose.popMatrix();
	}

	/** The arrow of the operating system, which is not in the framebuffer: black with a white edge, two pixels a cell. */
	private void pointer(GuiGraphicsExtractor graphics) {
		var pose = graphics.pose();
		pose.pushMatrix();
		// A mouse at rest is still to the pixel, and the game puts it on whole GUI pixels.
		pose.translate(Math.round(pointerX / 4) * 4, Math.round(pointerY / 4) * 4);
		pose.scale(2, 2);
		for (int row = 0; row < ARROW.length; row++) {
			String cells = ARROW[row];
			for (int from = 0; from < cells.length(); ) {
				char cell = cells.charAt(from);
				int to = from;
				while (to < cells.length() && cells.charAt(to) == cell) to++;
				if (cell != ' ') graphics.fill(from, row, to, row + 1, cell == 'X' ? 0xFF000000 : 0xFFFFFFFF);
				from = to;
			}
		}
		pose.popMatrix();
	}

	private void title(GuiGraphicsExtractor graphics, Font font) {
		double age = now - titleAt;
		if (age < 0) return;
		int shadeAlpha = (int) (0x8C * Mth.clamp(age / 0.5, 0, 1));
		graphics.fillGradient(0, 440, 1920, 1080, 0, shadeAlpha << 24);
		// Capitals about 155 px: letters drop in one after another and bounce once.
		float size = 22f, width = font.width(title) * size, x = 960 - width / 2;
		for (int i = 0; i < title.length(); i++) {
			String letter = title.substring(i, i + 1);
			float wide = font.width(letter) * size;
			double k = (age - i * 0.05) / 0.38;
			if (k > 0 && !letter.isBlank()) {
				float fall = k >= 1 ? 0 : (float) (k < 0.62 ? -420 * (1 - ease(k / 0.62) * ease(k / 0.62)) : -46 * Math.sin((k - 0.62) / 0.38 * Math.PI));
				var pose = graphics.pose();
				pose.pushMatrix();
				pose.translate(x, 526 + fall);
				pose.scale(size, size);
				// The depth of the letter: copies stepping down and away, darkest last.
				for (int depth = 4; depth >= 1; depth--) {
					pose.pushMatrix();
					pose.translate(depth * 0.22f, depth * 0.34f);
					graphics.text(font, letter, 0, 0, depth > 2 ? INK : 0xFF2B3442, false);
					pose.popMatrix();
				}
				for (int[] o : AROUND) {
					pose.pushMatrix();
					pose.translate(o[0] * 0.28f, o[1] * 0.28f);
					graphics.text(font, letter, 0, 0, INK, false);
					pose.popMatrix();
				}
				graphics.text(font, letter, 0, 0, WHITE, false);
				pose.popMatrix();
			}
			x += wide;
		}
		// Working, needs you, done: the three states an agent's dot takes.
		double dotsAt = title.length() * 0.05 + 0.3;
		int[] dots = {T3Hud.color(T3State.Status.WORKING), T3Hud.color(T3State.Status.NEEDS_YOU), T3Hud.color(T3State.Status.DONE)};
		for (int i = 0; i < dots.length; i++) {
			float scale = pop(age - dotsAt - i * 0.08);
			if (scale <= 0) continue;
			float cx = 960 + (i - 1) * 62, cy = 709, half = 13 * scale;
			graphics.fill((int) (cx - half - 4), (int) (cy - half - 4), (int) (cx + half + 4), (int) (cy + half + 4), INK);
			graphics.fill((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half), dots[i]);
		}
		// What it is, what it works with, where to get it: capitals 46 px, 66 px apart, each arriving right after the one above.
		double linesAt = dotsAt + 0.1;
		for (int i = 0; i < under.length; i++) {
			boolean link = i == under.length - 1;
			float in = (float) Mth.clamp((age - linesAt - i * 0.15) / 0.3, 0, 1);
			text(graphics, font, under[i], 960, 764 + i * 66 + (1 - in) * 18, 46 / 7f, link ? 0xFFD5DDE8 : WHITE, in);
		}
	}
}
