package dev.maxwellyoung.t3craft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Session identities, not addresses: reconnecting to the same world invalidates old work too. */
final class WorldBoundQueue<T> {
	private final Deque<T> items = new ArrayDeque<>();
	private Object level, connection;

	void add(Object level, Object connection, List<T> additions) {
		validate(level, connection);
		if (level == null || connection == null) return;
		this.level = level;
		this.connection = connection;
		items.addAll(additions);
	}

	boolean validate(Object level, Object connection) {
		boolean sameSession = level != null && connection != null && this.level == level && this.connection == connection;
		if (!sameSession) clear();
		return sameSession;
	}

	void clear() { items.clear(); level = null; connection = null; }
	boolean isEmpty() { return items.isEmpty(); }
	T peek() { return items.peek(); }
	T poll() { return items.poll(); }
}
