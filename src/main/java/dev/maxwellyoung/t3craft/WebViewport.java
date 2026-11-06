package dev.maxwellyoung.t3craft;

/**
 * Sizes the Chrome viewport so every frame pixel lands on exactly one framebuffer pixel.
 *
 * <p>The destination is a rectangle in GUI pixels. It covers {@code gui × guiScale} real framebuffer pixels, and that is
 * always the frame size Chrome renders, so the texture is drawn 1:1 with nearest filtering and is never stretched.
 *
 * <p>The device scale factor starts at the GUI scale, so one T3 CSS pixel is one GUI pixel. If that leaves T3 too few
 * CSS pixels for its layout (GUI scale 5 on a 2000-pixel window is only 400 CSS pixels), the scale is divided by the
 * smallest whole number {@code n} that gives at least {@code minCssWidth}. Because {@code n} is whole, the CSS size
 * ({@code gui × n}) stays an integer and the physical size stays exactly {@code gui × guiScale}.
 */
record WebViewport(int guiX, int guiY, int guiWidth, int guiHeight, int guiScale, int divisor) {
	/** The full T3 app: wide enough for T3's sidebar and thread side by side. */
	static final int LAPTOP_MIN_CSS = 1100;
	/** One thread: T3's compact (under 768 CSS px) layout, sidebar hidden. */
	static final int CARD_MIN_CSS = 480;
	static final int CARD_MAX_CSS = 767;

	static WebViewport fit(int guiX, int guiY, int guiWidth, int guiHeight, int guiScale, int minCssWidth) {
		return fit(guiX, guiY, guiWidth, guiHeight, guiScale, minCssWidth, Integer.MAX_VALUE);
	}

	static WebViewport fit(int guiX, int guiY, int guiWidth, int guiHeight, int guiScale, int minCssWidth, int maxCssWidth) {
		if (guiWidth <= 0 || guiHeight <= 0 || guiScale <= 0) throw new IllegalArgumentException("empty viewport");
		int n = 1;
		// n may not exceed the GUI scale: below one CSS pixel per framebuffer pixel text would get smaller than the
		// screen can show.
		while (guiWidth * n < minCssWidth && n < guiScale) n++;
		// Stay in T3's compact layout when asked (the agent card) even if that means fewer CSS pixels.
		while (n > 1 && guiWidth * n > maxCssWidth) n--;
		return new WebViewport(guiX, guiY, guiWidth, guiHeight, guiScale, n);
	}

	/**
	 * The agent card: centred, about 62% × 74% of the GUI, and never wider than {@link #CARD_MAX_CSS} GUI pixels, so it
	 * stays in T3's compact layout at every GUI scale (at scale 1 a 62% card would otherwise be the full desktop app).
	 */
	static WebViewport card(int fullW, int fullH, int guiScale) {
		int w = Math.max(160, Math.round(fullW * 0.62f)), h = Math.max(120, Math.round(fullH * 0.74f));
		w = Math.min(w, Math.min(fullW - 8, CARD_MAX_CSS));
		h = Math.min(h, fullH - 8);
		return fit((fullW - w) / 2, (fullH - h) / 2, w, h, guiScale, CARD_MIN_CSS, CARD_MAX_CSS);
	}

	/** Real framebuffer pixels covered by the destination, and the exact frame size Chrome must produce. */
	int physicalWidth() { return guiWidth * guiScale; }
	int physicalHeight() { return guiHeight * guiScale; }
	int physicalX() { return guiX * guiScale; }
	int physicalY() { return guiY * guiScale; }

	/** CSS viewport handed to Emulation.setDeviceMetricsOverride. */
	int cssWidth() { return guiWidth * divisor; }
	int cssHeight() { return guiHeight * divisor; }
	double deviceScaleFactor() { return (double) guiScale / divisor; }

	boolean contains(double guiMouseX, double guiMouseY) {
		return guiMouseX >= guiX && guiMouseY >= guiY && guiMouseX < guiX + guiWidth && guiMouseY < guiY + guiHeight;
	}

	/** GUI mouse position to the page's CSS pixel position. */
	double cssX(double guiMouseX) { return clamp((guiMouseX - guiX) * divisor, cssWidth() - 1); }
	double cssY(double guiMouseY) { return clamp((guiMouseY - guiY) * divisor, cssHeight() - 1); }

	/** True when a frame of this size can be drawn without any scaling. */
	boolean matches(int frameWidth, int frameHeight) { return frameWidth == physicalWidth() && frameHeight == physicalHeight(); }

	boolean sameSize(WebViewport other) {
		return other != null && other.cssWidth() == cssWidth() && other.cssHeight() == cssHeight() && other.guiScale == guiScale && other.divisor == divisor;
	}

	private static double clamp(double value, double max) { return Math.max(0, Math.min(max, value)); }
}
