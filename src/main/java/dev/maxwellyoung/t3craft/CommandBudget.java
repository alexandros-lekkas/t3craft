package dev.maxwellyoung.t3craft;

/** Half vanilla's sustained command rate, with no accumulated burst after an idle period. */
final class CommandBudget {
	private int cooldown;
	private long nextSend;
	void tick() { if (cooldown > 0) cooldown--; }
	boolean acquire(long now) {
		if (cooldown > 0 || now < nextSend) return false;
		cooldown = 40;
		nextSend = now + 2_000_000_000L;
		return true;
	}
}
