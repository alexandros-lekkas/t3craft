package dev.maxwellyoung.t3craft;

/** Turns wheel deltas into whole rows, keeping the remainder so trackpads' fractional deltas add up. */
final class T3Scroll {
	private double pending;

	/** Whole rows to move for this delta (same sign as {@code delta}); the fraction carries over. */
	int rows(double delta) {
		pending += delta;
		int whole = (int) pending;
		pending -= whole;
		return whole;
	}
}
