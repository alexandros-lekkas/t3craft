package dev.maxwellyoung.t3craft;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rate-limited usage snapshot reader. Calls run on T3State's existing I/O executor. A failed read replaces the
 * numbers with a short "unavailable" board (never last hour's numbers as if current) and is retried sooner.
 */
final class UsageBoardSource {
	private static final long INTERVAL_MS = 60_000, RETRY_MS = 15_000;
	private final T3State state;
	private volatile UsageBoardData data;
	private volatile long nextRequest;
	private final AtomicBoolean logged = new AtomicBoolean();
	UsageBoardSource(T3State state) { this.state = state; }
	/** The last answer, or null before the first one since pairing. */
	UsageBoardData data() { return data; }
	void refresh() {
		long now = System.currentTimeMillis();
		if (now < nextRequest) return;
		nextRequest = now + INTERVAL_MS;
		state.run(() -> {
			T3Backend backend = state.api();
			if (backend == null) throw new java.io.IOException("No T3 pairing");
			data = UsageBoardData.fromServerConfig(backend.usageConfig());
			logged.set(false);
		}, e -> {
			data = UsageBoardData.failed(e.getMessage());
			nextRequest = System.currentTimeMillis() + RETRY_MS;
			if (logged.compareAndSet(false, true)) T3Log.LOGGER.warn("Could not refresh usage board data: {}", e.getMessage());
		});
	}
	/** Unpaired: forget the numbers, so the next pairing (maybe another T3) is read at once. */
	void reset() { data = null; nextRequest = 0; }
}
