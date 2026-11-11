package dev.maxwellyoung.t3craft;

/**
 * When an office agent looks up at a player. A glance as you walk up, then back to work: nobody stares for as long as
 * you stand there. Each agent starts its look a few ticks apart (seeded by its thread), so a row of desks does not turn
 * as one. The one that needs you is the exception: it watches you whenever you are around. Pure, no Minecraft classes.
 */
final class AgentGlance {
	/** Come this close and an agent looks up. */
	static final double NEAR = 4.0;
	/** Go this far and the next approach counts as a new one. */
	static final double FAR = 6.0;
	/** A waiting agent watches you from this far. */
	static final double WAITING = 12.0;

	private boolean near;
	private int delay = -1, look, rest;

	/** A stable, non-negative number per thread: what keeps the agents out of step with each other. */
	static int seed(String threadId) {
		int h = threadId.hashCode() * 0x9E3779B1;
		return (h ^ h >>> 15) & 0x7fffffff;
	}

	/**
	 * One tick. {@code aimed}: the player's crosshair is on this agent (it looks back for as long as it is);
	 * {@code wants}: it is waiting for the player. True while it is looking at the player.
	 */
	boolean tick(double distance, boolean aimed, boolean wants, int seed) {
		if (wants) return distance < WAITING;
		if (aimed) look = Math.max(look, 12);
		if (distance > FAR) {
			near = false;
			delay = -1;
			rest = Math.min(rest, 60);
		} else if (distance < NEAR && look == 0 && rest == 0 && delay < 0) {
			// The first look comes a beat after you arrive; lingering earns another one much later.
			delay = near ? 0 : seed % 14;
			near = true;
		}
		if (rest > 0) rest--;
		if (delay >= 0 && delay-- == 0) look = 44 + seed % 30;
		if (look > 0 && --look == 0) rest = 240 + seed % 200;
		return look > 0;
	}
}
