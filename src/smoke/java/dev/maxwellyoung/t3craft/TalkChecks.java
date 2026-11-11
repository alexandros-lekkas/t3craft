package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agents talking in person, without Minecraft running: conversations detected from synthetic snapshots, every walk on
 * open office cells for all four office turns, the rate limits, and whole conversations played through the brain.
 */
public final class TalkChecks {
	private static final String OWNER = "http://127.0.0.1:25680";

	public static void main(String[] args) {
		checkDetection();
		checkRoutes();
		checkLimiter();
		checkConversation();
		checkWalk();
		checkLife();
		System.out.println("Agent talk checks passed");
	}

	// ---- Detection ----

	private static void checkDetection() {
		check(AgentTalk.parentOf(child("c", "p", T3State.Status.WORKING, "t1").raw()).equals("p"), "parent from the top-level field");
		JsonObject lineage = new JsonObject(), raw = new JsonObject(), inner = new JsonObject();
		inner.addProperty("parentThreadId", "p2"); inner.addProperty("relationshipToParent", "subagent"); raw.add("lineage", inner);
		check("p2".equals(AgentTalk.parentOf(raw)), "parent from lineage");
		check(AgentTalk.parentOf(lineage) == null, "top-level threads have no parent");
		check("p2".equals(AgentTalk.subagentParent(raw)), "a shell subagent names its parent");
		JsonObject fork = new JsonObject(), forkLineage = new JsonObject();
		forkLineage.addProperty("parentThreadId", "p"); forkLineage.addProperty("relationshipToParent", "fork"); fork.add("lineage", forkLineage);
		JsonObject side = raw.deepCopy(); side.getAsJsonObject("lineage").addProperty("sideChat", true);
		check(AgentTalk.subagentParent(fork) == null && AgentTalk.subagentParent(side) == null, "forks and side chats are not delegated work");
		check(T3Visibility.child(child("c", "p", T3State.Status.WORKING, "t1").raw()), "synthetic children are hidden from the office like real ones");

		Set<String> parents = Set.of("p"), online = Set.of(OWNER);
		var detector = new AgentTalk.Detector();
		check(detector.diff(List.of(child("old", "p", T3State.Status.WORKING, "t0")), parents, online).isEmpty(), "first snapshot is a baseline");
		var spawned = detector.diff(List.of(child("old", "p", T3State.Status.WORKING, "t0"), child("c1", "p", T3State.Status.WORKING, "t1"),
			child("stray", "hidden-parent", T3State.Status.WORKING, "t1")), parents, online);
		check(spawned.size() == 1 && spawned.getFirst().kind() == AgentTalk.Kind.DELEGATE && spawned.getFirst().childId().equals("c1"), "new child of an office parent is a delegation: " + spawned);
		check(spawned.getFirst().first().equals("Can you take “The greeting tests”?") && spawned.getFirst().parentFirst(), "delegation line from the title: " + spawned.getFirst().first());
		var done = detector.diff(List.of(child("old", "p", T3State.Status.WORKING, "t0"), child("c1", "p", T3State.Status.DONE, "t1")), parents, online);
		check(done.size() == 1 && done.getFirst().kind() == AgentTalk.Kind.REPORT && done.getFirst().first().startsWith("Done — ") && !done.getFirst().parentFirst(), "working → done is a report from the helper: " + done);
		var again = detector.diff(List.of(child("old", "p", T3State.Status.WORKING, "t0"), child("c1", "p", T3State.Status.WORKING, "t2")), parents, online);
		check(again.size() == 1 && again.getFirst().kind() == AgentTalk.Kind.FOLLOW_UP, "done → working on a new turn is a follow-up: " + again);
		var failed = detector.diff(List.of(child("old", "p", T3State.Status.WORKING, "t0"), child("c1", "p", T3State.Status.ERROR, "t2")), parents, online);
		check(failed.size() == 1 && failed.getFirst().first().startsWith("Hit a snag"), "a failed child reports a snag: " + failed);
		check(detector.diff(List.of(child("old", "p", T3State.Status.WORKING, "t0"), child("c1", "p", T3State.Status.ERROR, "t2")), parents, online).isEmpty(), "an unchanged snapshot says nothing");
		// MCP lists only active children: a working child dropping out of the list has finished.
		var vanished = detector.diff(List.of(child("c1", "p", T3State.Status.ERROR, "t2")), parents, online);
		check(vanished.size() == 1 && vanished.getFirst().kind() == AgentTalk.Kind.REPORT && vanished.getFirst().childId().equals("old")
			&& vanished.getFirst().child().status() == T3State.Status.DONE, "a working child leaving the active list reports back: " + vanished);
		var back = detector.diff(List.of(child("c1", "p", T3State.Status.ERROR, "t2"), child("old", "p", T3State.Status.WORKING, "t9")), parents, online);
		check(back.size() == 1 && back.getFirst().kind() == AgentTalk.Kind.FOLLOW_UP, "a known child coming back to work is a follow-up: " + back);
		check(detector.diff(List.of(), parents, Set.of()).isEmpty(), "a machine going offline drops its rows without reports");
		check(detector.diff(List.of(child("late", "p", T3State.Status.DONE, "t1")), parents, online).getFirst().kind() == AgentTalk.Kind.REPORT, "a child first seen finished reports straight away");

		check(AgentTalk.topic("Opus: agents walk over and talk").equals("agents walk over and talk") && AgentTalk.topic("Write the greeting tests").equals("Write the greeting tests"), "model label dropped: " + AgentTalk.topic("Opus: agents walk over and talk"));
		check(AgentTalk.topic("Opus review: PR#17 follow-ups").equals("PR#17 follow-ups"), "two-word label dropped");
		String longTopic = AgentTalk.topic("Refactor the whole websocket reconnection layer and its tests");
		check(longTopic.length() <= 27 && longTopic.endsWith("…"), "long titles cut at a word: " + longTopic);
		check(AgentTalk.topic("  ").equals("this") && AgentTalk.topic(null).equals("this"), "blank titles still read");
		System.out.println("Talk detection: delegate, follow-up, report (status, error, drop-out), baseline and offline quiet");
	}

	// ---- Routes ----

	private static void checkRoutes() {
		boolean[][] blocked = T3Office.blocked();
		int routes = 0;
		for (int floor : new int[] {0, 1, 2}) {
			int[] entry = AgentTalk.entry(floor);
			for (T3Office.Spot[] spots : List.of(T3Office.WORK, T3Office.NEEDS_YOU, T3Office.LOUNGE)) for (T3Office.Spot spot : spots) {
				String where = "floor " + floor + " from " + spot.x() + "," + spot.z();
				var route = AgentTalk.route(spot.x(), spot.z(), floor);
				check(route != null, "a route to the entry exists " + where);
				check(route.parentOut().size() <= AgentTalk.PARENT_STEPS, "the parent only walks a few cells " + where);
				double[] a = route.parentMeet(), b = route.helperMeet();
				check(Math.abs(a[0] - b[0]) <= 1 && Math.abs(a[1] - b[1]) <= 1 && !(a[0] == b[0] && a[1] == b[1]), "the two meet on neighbouring cells " + where);
				check(cell(route.entry()) [0] == entry[0] && cell(route.entry())[1] == entry[1], "the helper comes in at the entry " + where);
				// Every walk: parent out and back, helper in and out. Endpoints may be the seat or the doorway.
				List<List<double[]>> walks = new ArrayList<>();
				walks.add(prepend(new double[] {spot.x() + .5, spot.z() + .5}, route.parentOut()));
				walks.add(prepend(a, T3Office.path(cell(a)[0], cell(a)[1], spot.x(), spot.z())));
				walks.add(prepend(route.entry(), route.helperIn()));
				walks.add(prepend(b, route.helperOut()));
				Set<Long> ends = Set.of(key(spot.x(), spot.z()), key(entry[0], entry[1]));
				for (Rotation rotation : Rotation.values()) {
					var frame = new OfficeFrame(new BlockPos(-37, 64, 211), rotation);
					Set<BlockPos> solid = new HashSet<>();
					for (int x = 0; x < T3Office.WIDTH; x++) for (int z = 0; z < T3Office.LENGTH; z++) if (blocked[x][z]) solid.add(frame.block(x, floor * T3Office.FLOOR_HEIGHT, z));
					for (List<double[]> walk : walks) for (int i = 1; i < walk.size(); i++) {
						double[] from = walk.get(i - 1), to = walk.get(i);
						check(Math.abs(to[0] - from[0]) <= 1 && Math.abs(to[1] - from[1]) <= 1, "walks step one cell at a time " + where);
						for (int k = 0; k < 8; k++) {
							double t = (k + 0.5) / 8, x = from[0] + (to[0] - from[0]) * t, z = from[1] + (to[1] - from[1]) * t;
							int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
							boolean end = ends.contains(key(cx, cz));
							check(end || !blocked[cx][cz], "walk stays on open cells " + where + " at " + cx + "," + cz);
							BlockPos world = BlockPos.containing(frame.point(x, floor * T3Office.FLOOR_HEIGHT + 0.1, z));
							check(frame.local(world).equals(new BlockPos(cx, floor * T3Office.FLOOR_HEIGHT, cz)), "turned walk lands in the same design cell, " + rotation + " " + where);
							check(end || !solid.contains(world), "turned walk stays out of furniture, " + rotation + " " + where);
						}
					}
				}
				routes++;
			}
		}
		System.out.println("Talk routes: " + routes + " spots on three floors, open cells only, all four office turns");
	}

	// ---- Rate limiting ----

	private static void checkLimiter() {
		var limiter = new AgentTalk.Limiter();
		for (int i = 0; i < 4; i++) limiter.offer(AgentTalk.talk(AgentTalk.Kind.DELEGATE, "p" + i, child("c" + i, "p" + i, T3State.Status.WORKING, "t")), 0);
		var first = limiter.poll(1, t -> true);
		var second = limiter.poll(1, t -> true);
		check(first != null && second != null && limiter.poll(1, t -> true) == null && limiter.active() == AgentTalk.Limiter.MAX_ACTIVE, "at most two conversations at once");
		limiter.done(first, 100);
		var third = limiter.poll(101, t -> true);
		check(third != null && limiter.poll(101, t -> true) == null, "a finished talk frees a slot");
		limiter.done(second, 101); limiter.done(third, 101);
		// One parent, two children: never two conversations for one character.
		var busy = new AgentTalk.Limiter();
		busy.offer(AgentTalk.talk(AgentTalk.Kind.DELEGATE, "p", child("a", "p", T3State.Status.WORKING, "t")), 0);
		busy.offer(AgentTalk.talk(AgentTalk.Kind.DELEGATE, "p", child("b", "p", T3State.Status.WORKING, "t")), 0);
		var only = busy.poll(1, t -> true);
		check(only != null && busy.poll(1, t -> true) == null, "one conversation per parent at a time");
		busy.done(only, 50);
		check(busy.poll(51, t -> true) != null, "the parent's next child goes after");
		// Per-pair cooldown, and a newer talk replaces the queued one for its pair.
		var pair = new AgentTalk.Limiter();
		var delegate = AgentTalk.talk(AgentTalk.Kind.DELEGATE, "p", child("c", "p", T3State.Status.WORKING, "t"));
		pair.offer(delegate, 0);
		pair.done(pair.poll(0, t -> true), 200);
		pair.offer(AgentTalk.talk(AgentTalk.Kind.FOLLOW_UP, "p", child("c", "p", T3State.Status.WORKING, "t2")), 201);
		pair.offer(AgentTalk.talk(AgentTalk.Kind.REPORT, "p", child("c", "p", T3State.Status.DONE, "t2")), 202);
		check(pair.queued() == 1, "a newer talk replaces the queued one for its pair");
		check(pair.poll(200 + AgentTalk.Limiter.PAIR_COOLDOWN - 1, t -> true) == null, "a pair that just talked waits out its cooldown");
		var report = pair.poll(200 + AgentTalk.Limiter.PAIR_COOLDOWN, t -> true);
		check(report != null && report.kind() == AgentTalk.Kind.REPORT, "after the cooldown the newest talk plays");
		// Not ready (walking, say) keeps it queued; too old drops it.
		var stale = new AgentTalk.Limiter();
		stale.offer(delegate, 0);
		check(stale.poll(5, t -> false) == null && stale.queued() == 1, "a parent that is not ready keeps its talk queued");
		check(stale.poll(AgentTalk.Limiter.QUEUE_TTL + 1, t -> true) == null && stale.queued() == 0, "queued talks expire");
		var flood = new AgentTalk.Limiter();
		for (int i = 0; i < 20; i++) flood.offer(AgentTalk.talk(AgentTalk.Kind.DELEGATE, "p" + i, child("c" + i, "p" + i, T3State.Status.WORKING, "t")), 0);
		check(flood.queued() == AgentTalk.Limiter.MAX_QUEUE, "a burst of subagents keeps a short queue");
		System.out.println("Talk limits: " + AgentTalk.Limiter.MAX_ACTIVE + " at once, one per parent, " + AgentTalk.Limiter.PAIR_COOLDOWN / 20 + " s per pair, expiring queue");
	}

	// ---- Whole conversations ----

	private static void checkConversation() {
		var brain = new OfficeBrain();
		var prefs = new OfficeRoster.Preferences();
		List<T3State.ThreadRow> rows = new ArrayList<>();
		for (int i = 0; i < 10; i++) rows.add(row("P" + i, i == 3 ? T3State.Status.DONE : T3State.Status.WORKING));
		brain.sync(rows, List.of(OWNER), prefs);
		for (int t = 0; t < 2000; t++) brain.tick();
		check(brain.agents().values().stream().allMatch(OfficeBrain.Agent::settled), "everyone sat down first");

		var director = new AgentTalk.Director();
		director.observe(List.of(), brain, Set.of(OWNER));
		// Ten children at once from snapshots (eight on floor 0, two upstairs): the office still holds at most two talks.
		// P0 asks for its own child below, so it is left out here.
		List<T3State.ThreadRow> children = new ArrayList<>();
		for (int i = 1; i < 10; i++) children.add(child("C" + i, "P" + i, T3State.Status.WORKING, "t1"));
		director.observe(children, brain, Set.of(OWNER));
		Set<String> talked = new HashSet<>();
		List<String> order = new ArrayList<>();
		boolean[][] blocked = T3Office.blocked();
		int maxActive = 0, ticks = 0;
		while (ticks < 20 * 400 && (ticks < 5 || !director.conversations().isEmpty() || director.limiter().queued() > 0)) {
			brain.tick();
			director.tick(brain);
			ticks++;
			maxActive = Math.max(maxActive, director.conversations().size());
			for (var c : director.conversations()) {
				talked.add(c.talk.parentId());
				if (c.lineStarted()) order.add(c.talk.parentId() + ":" + (c.speaker() == c.parent ? "parent" : "helper"));
				for (var agent : List.of(c.parent, c.helper)) {
					int x = (int) Math.floor(agent.x), z = (int) Math.floor(agent.z);
					int[] entry = AgentTalk.entry(agent.floor);
					T3Office.Spot goal = c.parent.goal;
					boolean end = x == entry[0] && z == entry[1] || x == goal.x() && z == goal.z();
					check(end || !blocked[x][z], "nobody walks through furniture during a talk: " + agent.threadId + " at " + x + "," + z);
				}
				if (c.phase == AgentTalk.Phase.FIRST) {
					check(c.parent.sink == 0 && !c.parent.settled(), "the parent stands to talk");
					float toHelper = AgentTalk.yaw(c.parent.x, c.parent.z, c.helper.x, c.helper.z), toParent = AgentTalk.yaw(c.helper.x, c.helper.z, c.parent.x, c.parent.z);
					check(Math.abs(net.minecraft.util.Mth.wrapDegrees(c.parent.yaw - toHelper)) < 1 && Math.abs(net.minecraft.util.Mth.wrapDegrees(c.helper.yaw - toParent)) < 1,
						"the two face each other: " + c.parent.yaw + " vs " + toHelper + ", " + c.helper.yaw + " vs " + toParent);
					double apart = Math.hypot(c.parent.x - c.helper.x, c.parent.z - c.helper.z);
					check(apart >= 0.9 && apart <= 1.5, "they stand a step apart: " + apart);
				}
			}
		}
		check(maxActive >= 1 && maxActive <= AgentTalk.Limiter.MAX_ACTIVE, "at most two conversations at once: " + maxActive);
		// A burst keeps the newest few: the rest are dropped rather than replayed minutes late.
		check(talked.size() == AgentTalk.Limiter.MAX_QUEUE && talked.containsAll(List.of("P8", "P9")), "the newest queued delegations all played: " + talked);
		check(order.contains("P9:parent") && order.contains("P9:helper") && order.indexOf("P9:parent") < order.indexOf("P9:helper"), "the parent asks first: " + order);
		for (int t = 0; t < 2000; t++) { brain.tick(); director.tick(brain); }
		for (var agent : brain.agents().values()) {
			check(agent.settled() && !agent.errand && agent.sink == agent.goal.sink(), "everyone is back at their spot and pose: " + agent.threadId);
			check((int) Math.floor(agent.x) == agent.goal.x() && (int) Math.floor(agent.z) == agent.goal.z(), "back at the very same spot: " + agent.threadId);
		}

		// A report opens with the helper. A parent whose thread changes status mid-talk finishes the talk, then takes its new spot.
		director.stage(AgentTalk.talk(AgentTalk.Kind.REPORT, "P0", child("C0", "P0", T3State.Status.DONE, "t1")));
		for (int t = 0; t < AgentTalk.Limiter.PAIR_COOLDOWN + 5 && director.conversations().isEmpty(); t++) { brain.tick(); director.tick(brain); }
		var report = director.conversations().getFirst();
		rows.set(0, row("P0", T3State.Status.NEEDS_YOU));
		brain.sync(rows, List.of(OWNER), prefs);
		while (report.phase == AgentTalk.Phase.APPROACH) { brain.tick(); director.tick(brain); }
		check(report.speaker() == report.helper && report.line().startsWith("Done — "), "the helper reports first, even after the parent's status changed");
		boolean spoke = false;
		while (director.conversations().contains(report)) { brain.tick(); director.tick(brain); spoke |= report.phase == AgentTalk.Phase.SECOND; }
		check(spoke, "both lines are said");
		for (int t = 0; t < 2000; t++) { brain.tick(); director.tick(brain); }
		var p0 = brain.agents().get("P0");
		check(p0.settled() && p0.zone == OfficeBrain.Zone.NEEDS_YOU && (int) Math.floor(p0.x) == p0.goal.x() && (int) Math.floor(p0.z) == p0.goal.z(), "then the parent goes to its new spot");
		// A parent leaving the office mid-talk: the helper just leaves.
		director.stage(AgentTalk.talk(AgentTalk.Kind.DELEGATE, "P1", child("C1b", "P1", T3State.Status.WORKING, "t1")));
		for (int t = 0; t < 50 && director.conversations().isEmpty(); t++) { brain.tick(); director.tick(brain); }
		check(!director.conversations().isEmpty(), "a second child of P1 talks right away");
		rows.remove(1);
		brain.sync(rows, List.of(OWNER), prefs);
		for (int t = 0; t < 2000; t++) { brain.tick(); director.tick(brain); }
		check(director.conversations().isEmpty() && director.limiter().active() == 0, "a parent leaving ends its talk and frees the slot");
		// A parent halfway to a new spot turns aside to meet the helper, never through furniture, then carries on.
		rows.replaceAll(r -> r.id().equals("P2") ? row("P2", T3State.Status.NEEDS_YOU) : r);
		brain.sync(rows, List.of(OWNER), prefs);
		var p2 = brain.agents().get("P2");
		for (int t = 0; t < 60 + 25; t++) brain.tick(); // the status-change glance at the player, then off it goes
		check(!p2.settled() && p2.walking, "P2 is on its way");
		director.stage(AgentTalk.talk(AgentTalk.Kind.DELEGATE, "P2", child("C2b", "P2", T3State.Status.WORKING, "t1")));
		boolean met = false;
		for (int t = 0; t < 3000; t++) {
			brain.tick(); director.tick(brain);
			var c = director.of("P2");
			if (c != null) met |= c.phase == AgentTalk.Phase.FIRST;
			int x = (int) Math.floor(p2.x), z = (int) Math.floor(p2.z);
			check(x == p2.goal.x() && z == p2.goal.z() || !blocked[x][z], "a walking parent's detour stays on open cells: " + x + "," + z);
		}
		check(met && p2.settled() && p2.zone == OfficeBrain.Zone.NEEDS_YOU, "a walking parent talks, then reaches its new spot");
		System.out.println("Talk flow: " + talked.size() + " delegations, max " + maxActive + " at once, everyone back in their seat");
	}

	// ---- Walking ----

	/** Stride, turning and pace are AgentWalk's (see OfficeChecks); here, a talk's walks feed it a steady step. */
	private static void checkWalk() {
		check(OfficeBrain.WALK_SPEED == AgentWalk.SPEED, "conversation walks use the agents' shared walking pace");
		// A conversation's walk moves at the brain's pace every tick, so the legs keep a steady stride.
		var agent = new OfficeBrain.Agent("walker");
		agent.x = 3.5; agent.z = 3.5;
		agent.path.addAll(T3Office.path(3, 3, 3, 9));
		double last = agent.z;
		for (int t = 0; t < 20; t++) {
			OfficeBrain.step(agent);
			double moved = Math.abs(agent.z - last);
			last = agent.z;
			if (agent.walking) check(moved > OfficeBrain.WALK_SPEED * 0.5 && moved <= OfficeBrain.WALK_SPEED + 1e-9, "steady pace while walking: " + moved);
		}
		System.out.println("Talk walking: steady " + String.format(java.util.Locale.ROOT, "%.1f", OfficeBrain.WALK_SPEED * 20) + " blocks/s steps for AgentWalk");
	}

	// ---- Fixtures ----

	private static T3State.ThreadRow row(String id, T3State.Status status) {
		JsonObject raw = new JsonObject(); raw.addProperty("projectId", "project");
		return new T3State.ThreadRow(id, "Thread " + id, "Project", null, OWNER, status, Instant.now(), null, raw);
	}

	private static T3State.ThreadRow child(String id, String parent, T3State.Status status, String turn) {
		JsonObject raw = new JsonObject();
		raw.addProperty("projectId", "project");
		raw.addProperty("parentThreadId", parent);
		raw.addProperty("relationshipToParent", "subagent");
		JsonObject latest = new JsonObject(); latest.addProperty("turnId", turn); raw.add("latestTurn", latest);
		return new T3State.ThreadRow(id, "Opus: The greeting tests", "Project", null, OWNER, status, Instant.now(), null, raw);
	}

	private static List<double[]> prepend(double[] start, List<double[]> walk) {
		List<double[]> out = new ArrayList<>();
		out.add(new double[] {start[0], start[1]});
		for (double[] point : walk) out.add(new double[] {point[0], point[1]});
		return out;
	}

	private static int[] cell(double[] point) { return new int[] {(int) Math.floor(point[0]), (int) Math.floor(point[1])}; }
	private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
	// ---- Life: glances, idles, name tags ----

	private static void checkLife() {
		// A glance when you walk up, then back to work for a good while, however long you stand there.
		int seed = AgentGlance.seed("office-1"), other = AgentGlance.seed("office-2");
		AgentGlance glance = new AgentGlance();
		for (int i = 0; i < 40; i++) check(!glance.tick(10, false, false, seed), "nobody looks up at a player across the room");
		int looked = 0, first = -1, last = -1;
		for (int i = 0; i < 20 * 10; i++) if (glance.tick(2.5, false, false, seed)) { looked++; if (first < 0) first = i; last = i; }
		check(looked >= 40 && looked <= 80 && first <= 14 && last < 100, "one glance of a few seconds, then back to work: " + looked + " ticks from " + first + " to " + last);
		for (int i = 0; i < 40; i++) glance.tick(10, false, false, seed);
		for (int i = 0; i < 80; i++) glance.tick(10, false, false, seed);
		boolean again = false;
		for (int i = 0; i < 40; i++) again |= glance.tick(2.5, false, false, seed);
		check(again, "walking away and back earns another glance");
		check(new AgentGlance().tick(8, false, true, seed) && !new AgentGlance().tick(20, false, true, seed), "the agent that needs you watches you whenever you are around");
		AgentGlance aimed = new AgentGlance();
		for (int i = 0; i < 400; i++) check(aimed.tick(5, true, false, seed), "an agent under the crosshair looks back");
		check(seed != other && seed >= 0 && other >= 0, "each thread has its own stable seed");

		// The desk schedule: mostly typing, breaks of every kind, never two in a row, and neighbours out of step.
		java.util.EnumMap<AgentLife.Activity, Integer> seen = new java.util.EnumMap<>(AgentLife.Activity.class);
		int differ = 0, switches = 0;
		AgentLife.Activity before = null;
		for (long tick = 0; tick < 20 * 60 * 30; tick++) {
			AgentLife.Activity now = AgentLife.desk(seed, tick);
			seen.merge(now, 1, Integer::sum);
			if (now != AgentLife.desk(other, tick)) differ++;
			if (before != null && now != before) {
				switches++;
				check(now == AgentLife.Activity.TYPE || before == AgentLife.Activity.TYPE, "a break always follows typing, not another break");
			}
			before = now;
		}
		int total = 20 * 60 * 30;
		check(seen.getOrDefault(AgentLife.Activity.TYPE, 0) > total * 0.7, "a worker types most of the time");
		for (AgentLife.Activity idle : List.of(AgentLife.Activity.THINK, AgentLife.Activity.SIP, AgentLife.Activity.STRETCH))
			check(seen.getOrDefault(idle, 0) > total / 100, "a worker sometimes takes a break to " + idle);
		check(differ > total / 20 && switches > 20, "two workers are not in step");

		// Poses: typing keeps the hands on the keys with pauses; looking at you (FREE) leaves every arm to vanilla.
		float[] p = new float[AgentLife.CHANNELS];
		AgentLife.target(AgentLife.Activity.FREE, 10, seed, true, false, p);
		for (int i = AgentLife.RAX; i <= AgentLife.LAZ; i++) check(Float.isNaN(p[i]), "an agent looking at you does not type: its arms are vanilla's");
		float paused = 1, typing = 0;
		for (float t = 0; t < 400; t += 0.5f) {
			AgentLife.target(AgentLife.Activity.TYPE, t, seed, true, false, p);
			check(p[AgentLife.RAX] < -1 && p[AgentLife.RAX] > -1.3f && p[AgentLife.LAX] < -1 && p[AgentLife.LAX] > -1.3f, "typing hands stay over the keyboard");
			float b = AgentLife.burst(seed, t);
			paused = Math.min(paused, b); typing = Math.max(typing, b);
		}
		check(paused == 0 && typing == 1, "typing comes in bursts with pauses");
		AgentLife.target(AgentLife.Activity.STRETCH, 5, seed, false, false, p);
		check(p[AgentLife.LEAN] == 0, "only a seated agent leans back");
		AgentLife.target(AgentLife.Activity.WAVE, 20, seed, false, false, p);
		check(p[AgentLife.RAX] < -2.5f && Float.isNaN(p[AgentLife.LAX]), "the agent that needs you raises one hand");

		// Name tags: a dot across the room, a short title close by, the whole thing under the crosshair, and needs-you always named.
		java.util.function.ToIntFunction<String> width = text -> text.length() * 6;
		java.util.function.BiFunction<String, Integer, String> cut = (text, max) -> text.substring(0, Math.min(text.length(), max / 6));
		String name = "\u25CF Design the landing page for the launch";
		var far = AgentTag.look(name, "WORKING", 20, false, false, true, 0, width, cut);
		var near = AgentTag.look(name, "WORKING", 5, false, false, true, 0, width, cut);
		var aim = AgentTag.look(name, "WORKING", 5, true, false, true, 0, width, cut);
		var needs = AgentTag.look("\u25CF Choose a direction", "NEEDS YOU", 20, false, false, false, 0, width, cut);
		check(far.text().getString().equals("\u25CF"), "across the room a tag is its status dot");
		check(near.text().getString().endsWith("…") && width.applyAsInt(near.text().getString()) * near.scale() <= 80, "a title fits between two desks: " + near.text().getString());
		check(!near.text().getString().contains("WORKING") && !near.text().getString().contains("working"), "one line: no status word until you look");
		check(aim.text().getString().contains("Design the landing page for") && aim.text().getString().endsWith("working"), "under the crosshair: the title and the status in words");
		check(needs.text().getString().equals("\u25CF Choose a direction") && needs.scale() > near.scale() && needs.scale() > 1, "the agent that needs you is named from afar, and larger");
		check(AgentTag.look(name, "DONE", 5, false, true, true, 0, width, cut).text().getString().equals("\u25CF"), "lounge seats show a dot until you look at one");
		check(AgentTag.look(name, "WORKING", 40, false, false, true, 0, width, cut) == null, "no tag beyond 32 blocks");
		check(AgentTag.color("WORKING") != AgentTag.color("NEEDS YOU") && AgentTag.color("DONE") != AgentTag.color("WORKING"), "each status has its own colour");
		System.out.println("Agent life: one glance then back to work, staggered desk idles, hands off the keys when looking, one-line name tags");
	}

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
