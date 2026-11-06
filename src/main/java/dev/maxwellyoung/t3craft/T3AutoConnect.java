package dev.maxwellyoung.t3craft;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Zero setup: finds the T3 Code running on this computer and pairs with it through T3's own CLI,
 * so there is nothing to paste and nothing to type. Keeps looking until T3 is running, and can be
 * started again when a pairing stops working.
 */
final class T3AutoConnect {
	private static final long RETRY_MS = 15_000;
	private static final long MAX_RETRY_MS = 5 * 60_000;
	/** What the minted link may grant: only what the mod uses, never the CLI's full default set. */
	static final List<String> SCOPES = List.of("orchestration:read", "orchestration:operate");
	private static final AtomicBoolean running = new AtomicBoolean();

	/** Turns the local T3 into a pairing link; the production one asks T3's bundled CLI. */
	interface Mint { String url(T3Web.Local local) throws Exception; }

	private T3AutoConnect() {}

	/**
	 * Spacing for pairing again after T3 refuses a pairing: the first re-pair is immediate, each one after waits
	 * twice as long as the last (15 s up to five minutes) until a pairing works again.
	 */
	static final class Backoff {
		private long notBefore;
		private long delay = RETRY_MS;

		boolean ready(long now) { return now >= notBefore; }

		void attempted(long now) {
			notBefore = now + delay;
			delay = Math.min(delay * 2, MAX_RETRY_MS);
		}

		void reset() { notBefore = 0; delay = RETRY_MS; }

		long delayForTest() { return delay; }
	}

	/**
	 * One attempt: null when no T3 runs on this computer; throws when it runs but pairing fails. The protocol is
	 * checked before any credential is minted, so an incompatible T3 costs one HTTP request, not a CLI run.
	 */
	static T3Config.Environment pairLocal(Path home, Mint mint) throws Exception {
		T3Web.Local local = T3Web.find(home);
		if (local == null) return null;
		T3Api.probe(local.origin());
		T3Api.Pairing pairing = T3Api.pair(mint.url(local), "Minecraft");
		return new T3Config.Environment(T3Api.environmentLabel(pairing.baseUrl()), pairing.baseUrl(), pairing.accessToken());
	}

	/** Whether the background pairing is running right now. */
	static boolean running() { return running.get(); }

	/**
	 * Retries in the background while {@code stillNeeded}; {@code paired} gets the environment off-thread. A second
	 * call while one runs is a no-op. T3 not running costs one refused connection every 15 s; a T3 that refuses to
	 * pair backs off, doubling up to five minutes, so a broken exchange never becomes a pairing storm.
	 */
	static void ensureStarted(BooleanSupplier stillNeeded, Consumer<T3Config.Environment> paired) {
		if (!running.compareAndSet(false, true)) return;
		Thread thread = new Thread(() -> {
			try {
				String lastNote = null;
				long delay = RETRY_MS;
				while (stillNeeded.getAsBoolean()) {
					String note;
					try {
						T3Config.Environment env = pairLocal(T3Web.home(), local -> T3Web.mintPairingUrl(local, "Minecraft", SCOPES, true));
						if (env != null) { paired.accept(env); return; }
						note = "T3 Code is not running on this computer yet; connecting as soon as it is";
						delay = RETRY_MS;
					} catch (Exception e) {
						String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
						note = "Could not connect to the local T3 Code (" + why + "); trying again in " + delay / 1000 + " s";
						delay = Math.min(delay * 2, MAX_RETRY_MS);
					}
					// The log says it once per cause, not every attempt.
					if (!Objects.equals(note, lastNote)) T3Log.LOGGER.info(note);
					lastNote = note;
					Thread.sleep(delay);
				}
			} catch (InterruptedException ignored) {
			} finally {
				running.set(false);
			}
		}, "t3craft-autoconnect");
		thread.setDaemon(true);
		thread.start();
	}
}
