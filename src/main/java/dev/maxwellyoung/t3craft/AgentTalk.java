package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Agents talking in person. When a thread hands work to a subagent, sends it more, or gets its result back, the
 * parent's character gets up from its desk, meets a "Helper" visitor walking in from the entry, they trade two short
 * lines in speech bubbles, and both go back where they came from.
 *
 * <p>Pure office logic with no Minecraft classes, like {@link OfficeBrain}: everything is in the design's own
 * coordinates and {@link OfficeFrame} turns it with the office. {@link OfficeTalks} draws it in the client office.
 * <ul>
 * <li>{@link Detector}: conversations from consecutive snapshots of the hidden child threads.</li>
 * <li>{@link Limiter}: at most {@value Limiter#MAX_ACTIVE} at once, one per parent, a cooldown per pair.</li>
 * <li>{@link #route}: where the two walk, along open office cells, and where they stand.</li>
 * <li>{@link Director}: runs each conversation: approach, two lines, back to the desk.</li>
 * </ul>
 */
final class AgentTalk {
	private AgentTalk() {}

	enum Kind {
		/** A parent just spawned this subagent. */
		DELEGATE,
		/** A parent sent an idle subagent more work. */
		FOLLOW_UP,
		/** The subagent finished (or failed) and its result went back to the parent. */
		REPORT
	}

	/** One conversation to show: who talks to whom, and the two lines, first speaker first. */
	record Talk(Kind kind, String parentId, T3State.ThreadRow child, String first, String second) {
		String childId() { return child.id(); }
		String pair() { return parentId + "\n" + child.id(); }
		/** Delegations and follow-ups open with the parent; a report opens with the helper. */
		boolean parentFirst() { return kind != Kind.REPORT; }
	}

	/** The thread a child row names as its parent, from either wire protocol; null for top-level threads. */
	static String parentOf(JsonObject raw) {
		if (raw == null) return null;
		for (String key : List.of("parentThreadId", "parentId")) {
			String value = T3Protocol.string(raw, key);
			if (value != null && !value.isBlank()) return value;
		}
		JsonObject lineage = T3Protocol.object(raw, "lineage");
		if (lineage != null) for (String key : List.of("parentThreadId", "parentId")) {
			String value = T3Protocol.string(lineage, key);
			if (value != null && !value.isBlank()) return value;
		}
		return null;
	}

	/**
	 * The parent of a subagent (T3's {@code relationshipToParent: "subagent"}, top level on MCP rows, under
	 * {@code lineage} in the shell); null for top-level threads, forks and side chats, which are not delegated work.
	 */
	static String subagentParent(JsonObject raw) {
		if (raw == null) return null;
		JsonObject lineage = T3Protocol.object(raw, "lineage");
		boolean subagent = "subagent".equals(T3Protocol.string(raw, "relationshipToParent"))
			|| lineage != null && "subagent".equals(T3Protocol.string(lineage, "relationshipToParent"));
		return subagent && !T3Visibility.flag(raw, "sideChat") && (lineage == null || !T3Visibility.flag(lineage, "sideChat")) ? parentOf(raw) : null;
	}

	// ---- Lines ----

	private static final int TOPIC = 26;

	/** What a child is about, short enough for a bubble: its title without a leading "Model:" style label, cut at a word. */
	static String topic(String title) {
		String text = title == null ? "" : title.replaceAll("[\\p{Cntrl}]", " ").strip();
		text = text.replaceFirst("^[\\p{L}\\p{N} ._#-]{1,20}:\\s+", "");
		if (text.length() > TOPIC) {
			int cut = text.lastIndexOf(' ', TOPIC);
			text = text.substring(0, cut > TOPIC / 2 ? cut : TOPIC).strip() + "…";
		}
		return text.isBlank() ? "this" : text;
	}

	static Talk talk(Kind kind, String parentId, T3State.ThreadRow child) {
		// Quoted, so a title reads the same whether it names a thing ("PR#17 follow-ups") or a task ("Write the tests").
		String topic = "“" + topic(child.title()) + "”";
		return switch (kind) {
			case DELEGATE -> new Talk(kind, parentId, child, "Can you take " + topic + "?", "On it!");
			case FOLLOW_UP -> new Talk(kind, parentId, child, "One more thing on " + topic + ".", "Sure, back on it.");
			case REPORT -> child.status() == T3State.Status.ERROR
				? new Talk(kind, parentId, child, "Hit a snag on " + topic + ".", "I'll take a look.")
				: new Talk(kind, parentId, child, "Done — " + topic + "!", "Thanks!");
		};
	}

	// ---- Detection ----

	/**
	 * Diffs consecutive snapshots of child threads. The first snapshot is only a baseline, so joining a world never
	 * replays old delegations. Only children whose parent has a character in the office count.
	 */
	static final class Detector {
		private record Seen(String parentId, String owner, T3State.Status status, String turn, T3State.ThreadRow row) {}

		private Map<String, Seen> last;
		/** Children seen before: one that comes back to work after dropping out of the active list is a follow-up. */
		private final java.util.LinkedHashSet<String> known = new java.util.LinkedHashSet<>();

		List<Talk> diff(Collection<T3State.ThreadRow> children, Set<String> parentsInOffice, Set<String> onlineOwners) {
			Map<String, Seen> now = new LinkedHashMap<>();
			for (T3State.ThreadRow row : children) {
				String parent = subagentParent(row.raw());
				if (parent != null) now.put(row.id(), new Seen(parent, row.ownerKey(), row.status(), turn(row.raw()), row));
			}
			List<Talk> talks = new ArrayList<>();
			if (last != null) {
				for (Map.Entry<String, Seen> entry : now.entrySet()) {
					Seen seen = entry.getValue(), before = last.get(entry.getKey());
					if (!parentsInOffice.contains(seen.parentId)) continue;
					if (before == null) {
						Kind kind = finished(seen.status) ? Kind.REPORT : known.contains(entry.getKey()) ? Kind.FOLLOW_UP : Kind.DELEGATE;
						talks.add(talk(kind, seen.parentId, seen.row));
					} else if (active(before.status) && finished(seen.status)) {
						talks.add(talk(Kind.REPORT, seen.parentId, seen.row));
					} else if (!active(before.status) && seen.status == T3State.Status.WORKING && before.turn != null && seen.turn != null
						&& !seen.turn.equals(before.turn)) {
						talks.add(talk(Kind.FOLLOW_UP, seen.parentId, seen.row));
					}
				}
				// T3's MCP lists only active children: a working child that drops out of the list has finished. A machine
				// going offline drops all of its rows at once, which says nothing about them.
				for (Map.Entry<String, Seen> entry : last.entrySet()) {
					Seen before = entry.getValue();
					if (now.containsKey(entry.getKey()) || before.status != T3State.Status.WORKING) continue;
					if (!onlineOwners.contains(before.owner) || !parentsInOffice.contains(before.parentId)) continue;
					T3State.ThreadRow row = before.row;
					T3State.ThreadRow done = new T3State.ThreadRow(row.id(), row.title(), row.projectTitle(), row.environment(), row.ownerKey(),
						T3State.Status.DONE, row.workingSince(), row.step(), row.raw());
					talks.add(talk(Kind.REPORT, before.parentId, done));
				}
			}
			last = now;
			known.addAll(now.keySet());
			for (Iterator<String> it = known.iterator(); known.size() > 512 && it.hasNext(); ) { it.next(); it.remove(); }
			return talks;
		}

		void reset() { last = null; known.clear(); }

		private static boolean active(T3State.Status status) {
			return status == T3State.Status.WORKING || status == T3State.Status.NEEDS_YOU;
		}

		private static boolean finished(T3State.Status status) {
			return status == T3State.Status.DONE || status == T3State.Status.ERROR;
		}

		private static String turn(JsonObject raw) {
			JsonObject latest = T3Protocol.object(raw, "latestTurn");
			return latest == null ? null : T3Protocol.string(latest, "turnId");
		}
	}

	// ---- Rate limiting ----

	/**
	 * Keeps the office calm: at most {@value #MAX_ACTIVE} conversations at once, never two for one parent, and a pair
	 * that just talked waits {@value #PAIR_COOLDOWN} ticks before talking again. Waiting talks expire; a newer talk for
	 * the same pair replaces the queued one.
	 */
	static final class Limiter {
		static final int MAX_ACTIVE = 2;
		static final int PAIR_COOLDOWN = 20 * 20;
		static final int QUEUE_TTL = 20 * 90;
		static final int MAX_QUEUE = 6;

		private record Pending(Talk talk, long at) {}

		private final ArrayDeque<Pending> queue = new ArrayDeque<>();
		private final Map<String, Long> ended = new HashMap<>();
		private final Set<String> busyParents = new HashSet<>();
		private final Set<String> busyPairs = new HashSet<>();

		void offer(Talk talk, long now) {
			// A newer talk for the same pair says more (the result supersedes the hand-off it follows).
			queue.removeIf(p -> p.talk.pair().equals(talk.pair()));
			while (queue.size() >= MAX_QUEUE) queue.poll();
			queue.add(new Pending(talk, now));
		}

		/** The next talk that may start now, if any; it then counts as active until {@link #done}. */
		Talk poll(long now, Predicate<Talk> ready) {
			queue.removeIf(p -> now - p.at > QUEUE_TTL);
			if (busyParents.size() >= MAX_ACTIVE) return null;
			for (Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
				Talk talk = it.next().talk;
				if (busyParents.contains(talk.parentId()) || busyPairs.contains(talk.pair())) continue;
				Long last = ended.get(talk.pair());
				if (last != null && now - last < PAIR_COOLDOWN) continue;
				if (!ready.test(talk)) continue;
				it.remove();
				busyParents.add(talk.parentId());
				busyPairs.add(talk.pair());
				return talk;
			}
			return null;
		}

		void done(Talk talk, long now) {
			busyParents.remove(talk.parentId());
			busyPairs.remove(talk.pair());
			ended.put(talk.pair(), now);
			ended.values().removeIf(at -> now - at > PAIR_COOLDOWN);
		}

		int active() { return busyParents.size(); }
		int queued() { return queue.size(); }

		void clear() {
			queue.clear();
			ended.clear();
			busyParents.clear();
			busyPairs.clear();
		}
	}

	// ---- Routes ----

	/** The parent walks at most this many cells from its desk; the helper covers the rest. */
	static final int PARENT_STEPS = 3;

	/**
	 * Where a conversation happens, as design cell centres on the parent's floor: the parent's walk out from its spot,
	 * the helper's walk in from the entry and back out. The two end up on neighbouring cells of one open route, so
	 * neither passes through furniture or the other.
	 */
	record Route(double[] entry, List<double[]> parentOut, List<double[]> helperIn, List<double[]> helperOut) {
		double[] parentMeet() { return parentOut.getLast(); }
		double[] helperMeet() { return helperIn.isEmpty() ? entry : helperIn.getLast(); }
	}

	/** The cell a visitor appears in: the entry door on the ground floor, the stair landing above. */
	static int[] entry(int floor) {
		return floor == 0 ? new int[] {T3Office.DOOR[0], T3Office.DOOR[1]} : new int[] {T3Office.LADDER[0] - 1, T3Office.LADDER[1]};
	}

	/** A route from the parent's cell toward its floor's entry, or null when there is no open walk. */
	static Route route(int fromX, int fromZ, int floor) {
		int[] entry = entry(floor);
		List<double[]> walk = T3Office.find(fromX, fromZ, entry[0], entry[1]);
		if (walk == null || walk.size() < 2) return null;
		int n = walk.size();
		int parentStop = Math.min(PARENT_STEPS - 1, n - 2), helperStop = parentStop + 1;
		List<double[]> parentOut = new ArrayList<>(walk.subList(0, parentStop + 1));
		List<double[]> helperIn = new ArrayList<>();
		for (int i = n - 2; i >= helperStop; i--) helperIn.add(walk.get(i));
		List<double[]> helperOut = new ArrayList<>(walk.subList(helperStop + 1, n));
		return new Route(walk.getLast(), parentOut, helperIn, helperOut);
	}

	/** The yaw (design frame, Minecraft convention) that faces from one point to another. */
	static float yaw(double fromX, double fromZ, double toX, double toZ) {
		return (float) (Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX)) - 90);
	}

	// ---- Conversations ----

	/** How long each line stays up, in ticks (about three seconds). */
	static final int LINE_TICKS = 60;
	/** Standing up from a seat before walking off. */
	static final int STAND_TICKS = 8;
	/** Give up and go back if the two have not met by then. */
	static final int APPROACH_TIMEOUT = 20 * 30;

	enum Phase { APPROACH, FIRST, SECOND, LEAVE, DONE }

	/** One conversation in progress: the parent's character, the visiting helper, and what is being said. */
	static final class Conversation {
		final Talk talk;
		final OfficeBrain.Agent parent;
		final OfficeBrain.Agent helper;
		final Route route;
		Phase phase = Phase.APPROACH;
		int ticks;

		Conversation(Talk talk, OfficeBrain.Agent parent, OfficeBrain.Agent helper, Route route) {
			this.talk = talk;
			this.parent = parent;
			this.helper = helper;
			this.route = route;
		}

		/** Who is speaking right now, or null between lines. */
		OfficeBrain.Agent speaker() {
			return switch (phase) {
				case FIRST -> talk.parentFirst() ? parent : helper;
				case SECOND -> talk.parentFirst() ? helper : parent;
				default -> null;
			};
		}

		/** The line being spoken, or null. */
		String line() {
			return switch (phase) {
				case FIRST -> talk.first();
				case SECOND -> talk.second();
				default -> null;
			};
		}

		/** True on the first tick of a line, when the bubble and its puff appear. */
		boolean lineStarted() {
			return (phase == Phase.FIRST || phase == Phase.SECOND) && ticks == 1;
		}

		/** The other character in the conversation. */
		OfficeBrain.Agent partner(OfficeBrain.Agent who) {
			return who == parent ? helper : parent;
		}
	}

	/** Turns snapshots into conversations and walks them through, one game tick at a time. */
	static final class Director {
		private final Detector detector = new Detector();
		private final Limiter limiter = new Limiter();
		private final List<Conversation> active = new ArrayList<>();
		private long clock;

		/** Look for new conversations in a snapshot's child threads. */
		void observe(Collection<T3State.ThreadRow> children, OfficeBrain brain, Set<String> onlineOwners) {
			for (Talk talk : detector.diff(children, brain.agents().keySet(), onlineOwners)) limiter.offer(talk, clock);
		}

		/** Queue a conversation directly (dev showcase and self-test). */
		void stage(Talk talk) {
			limiter.offer(talk, clock);
		}

		/** One game tick, after {@link OfficeBrain#tick}: start what may start, move every conversation along. */
		void tick(OfficeBrain brain) {
			clock++;
			for (Talk talk; (talk = limiter.poll(clock, t -> ready(brain, t))) != null; ) {
				Conversation started = start(brain, talk);
				if (started == null) limiter.done(talk, clock); else active.add(started);
			}
			for (Iterator<Conversation> it = active.iterator(); it.hasNext(); ) {
				Conversation conversation = it.next();
				advance(brain, conversation);
				if (conversation.phase == Phase.DONE) {
					limiter.done(conversation.talk, clock);
					it.remove();
				}
			}
		}

		List<Conversation> conversations() { return List.copyOf(active); }

		/** The conversation this thread's character is in, if any. */
		Conversation of(String threadId) {
			for (Conversation conversation : active) if (conversation.parent.threadId.equals(threadId)) return conversation;
			return null;
		}

		Limiter limiter() { return limiter; }

		void clear() {
			for (Conversation conversation : active) conversation.parent.errand = false;
			active.clear();
			limiter.clear();
			detector.reset();
		}

		private static boolean ready(OfficeBrain brain, Talk talk) {
			OfficeBrain.Agent parent = brain.agents().get(talk.parentId());
			// Seated, or on its way somewhere: a parent in the middle of a walk turns aside to meet the helper.
			return parent != null && !parent.errand;
		}

		private static Conversation start(OfficeBrain brain, Talk talk) {
			OfficeBrain.Agent parent = brain.agents().get(talk.parentId());
			if (parent == null) return null;
			int cellX = (int) Math.floor(parent.x), cellZ = (int) Math.floor(parent.z);
			Route route = route(cellX, cellZ, parent.floor);
			if (route == null) return null;
			double[] meet = route.parentMeet(), other = route.helperMeet();
			parent.errand = true;
			parent.errandYaw = yaw(meet[0], meet[1], other[0], other[1]);
			boolean seated = parent.sink > 0;
			parent.sink = 0; // stand up before walking off
			parent.path.clear();
			parent.pause = 0;
			// From the middle of its cell, so every step is centre to centre along open cells; a seated agent stands first.
			parent.path.add(new double[] {cellX + 0.5, cellZ + 0.5, seated ? STAND_TICKS : 0, Float.NaN});
			parent.path.addAll(route.parentOut());
			OfficeBrain.Agent helper = new OfficeBrain.Agent(talk.childId());
			helper.title = talk.child().title();
			helper.status = talk.child().status();
			helper.floor = parent.floor;
			helper.x = route.entry()[0];
			helper.z = route.entry()[1];
			helper.errand = true;
			helper.errandYaw = yaw(other[0], other[1], meet[0], meet[1]);
			helper.yaw = helper.errandYaw;
			helper.path.addAll(route.helperIn());
			return new Conversation(talk, parent, helper, route);
		}

		private void advance(OfficeBrain brain, Conversation c) {
			c.ticks++;
			OfficeBrain.step(c.helper);
			// The parent's own brain walks it; the thread leaving the office ends the talk early.
			boolean parentHere = brain.agents().get(c.parent.threadId) == c.parent && c.parent.errand;
			if (c.phase != Phase.LEAVE && !parentHere) leave(c, false);
			switch (c.phase) {
				case APPROACH -> {
					if (c.parent.arrived() && c.helper.arrived()) next(c, Phase.FIRST);
					else if (c.ticks > APPROACH_TIMEOUT) leave(c, true);
				}
				case FIRST -> { if (c.ticks >= LINE_TICKS) next(c, Phase.SECOND); }
				case SECOND -> { if (c.ticks >= LINE_TICKS) leave(c, true); }
				case LEAVE -> { if (c.helper.arrived()) c.phase = Phase.DONE; }
				case DONE -> {}
			}
		}

		private static void next(Conversation c, Phase phase) {
			c.phase = phase;
			c.ticks = 0;
		}

		/** Both head back: the parent to its spot (if it still owns its errand), the helper out the way it came. */
		private static void leave(Conversation c, boolean parentBack) {
			next(c, Phase.LEAVE);
			if (parentBack && c.parent.errand) {
				c.parent.errand = false;
				c.parent.path.clear();
				T3Office.Spot goal = c.parent.goal;
				if (goal != null) c.parent.path.addAll(T3Office.path((int) Math.floor(c.parent.x), (int) Math.floor(c.parent.z), goal.x(), goal.z()));
			}
			c.helper.errand = false;
			c.helper.path.clear();
			int fromX = (int) Math.floor(c.helper.x), fromZ = (int) Math.floor(c.helper.z);
			int[] entry = entry(c.helper.floor);
			if (fromX != entry[0] || fromZ != entry[1]) c.helper.path.addAll(T3Office.path(fromX, fromZ, entry[0], entry[1]));
		}
	}
}
