package dev.maxwellyoung.t3craft;

import java.util.List;

/**
 * Zero-setup rules, kept free of Minecraft so they can be checked headlessly: which thread a command
 * talks to when the player never picked one, and how little the mod says in chat on its own.
 */
final class FirstRun {
	private FirstRun() {}

	/**
	 * The thread a command talks to. The one the player chose wins while it is still listed (a settled, archived
	 * or child thread no longer counts); otherwise the thread that needs them, else the most recently active one
	 * ({@code rows} arrive in that order); null with no threads.
	 */
	static T3State.ThreadRow defaultThread(List<T3State.ThreadRow> rows, String chosenId) {
		if (chosenId != null) for (T3State.ThreadRow row : rows) if (row.id().equals(chosenId)) return row;
		for (T3State.ThreadRow row : rows) if (row.status() == T3State.Status.NEEDS_YOU) return row;
		return rows.isEmpty() ? null : rows.getFirst();
	}

	/** The one line the mod says by itself: once per game, when T3 is connected and the player is in a world. */
	static String connectedLine(String openKey) {
		return "T3 connected · press " + openKey + " to open";
	}

	/** One line of {@code /t3} help and the command a click on it runs. */
	record Tip(String text, String command) {}

	/** The whole of {@code /t3}: what a player needs and nothing else. */
	static List<Tip> help(String openKey) {
		return List.of(
			new Tip("Press " + openKey + " to open T3 (or click here).", "/t3 open"),
			new Tip("/t3 office · go to your office, or build one where you stand.", "/t3 office"));
	}

	/** Chat budget for connection state: exactly one line per game, nothing on reconnects, nothing while unpaired. */
	static final class ConnectNotice {
		private boolean said;

		/** True exactly once: the first time T3 is connected while a player can read chat. */
		boolean due(boolean connected, boolean inWorld) {
			if (said || !connected || !inWorld) return false;
			said = true;
			return true;
		}
	}
}
