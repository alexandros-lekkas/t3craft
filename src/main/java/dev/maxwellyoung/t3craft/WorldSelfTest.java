package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Dev-only walkthrough, enabled with {@code -Dt3craft.worldtest=<place>} (a Place label such as
 * {@code Lakeside} or {@code River Bend}): from the title screen, press T3 World, pick the place,
 * screenshot the New T3 World screen, press Create, screenshot the arrival, then look at the
 * office from outside and in, and the usage board on its wall by day and by night. Paired with the office fixture ({@code -Pconfig=run/config/office-fixture.json})
 * it also waits for the agents to sit down and checks that every working agent is at its desk,
 * in the world, wherever the office was turned. Drives the real screens through their buttons, then quits.
 */
final class WorldSelfTest {
	private enum Step { TITLE, FORM, ARRIVE, OUTSIDE, DESKS, BOARD, TALK, LIFE, LOOK, DONE }

	private final T3CraftClient mod;
	private final String place;
	private Step step = Step.TITLE;
	private int wait, deadline = 20 * 60 * 20;
	private BlockPos arrivedAt;
	private float arrivedYaw, arrivedPitch;
	private OfficeFrame frame;
	/**
	 * {@code -Dt3craft.worldtalk=<seconds>} waits that long after the desks for a real conversation (a thread spawning
	 * a subagent) and photographs it; {@code <seconds>:<title>} only counts parents whose title contains that text;
	 * {@code stage} starts one from the first seated worker instead.
	 */
	private final String talk = System.getProperty("t3craft.worldtalk");
	private AgentTalk.Conversation conversation;
	private final AgentLifeShots life;
	private final java.util.Set<String> talkShots = new java.util.HashSet<>();
	private boolean helperSeen, bubbleSeen, helperLegs, parentLegs;
	private AgentTalk.Phase lastPhase;
	/** Conversations photographed so far; after the first, one more (the result coming back) is waited for briefly. */
	private int talked;
	private boolean staged;
	private int boardFrames;
	/** Providers on the usage board when it was photographed: names, or why there are none. */
	private String boardShows = "";
	/** The marker as last copied on the server thread (SavedData belongs to it), and a copy on its way. */
	private Marker marker;
	private java.util.concurrent.CompletableFuture<Marker> reading;

	private record Marker(String name, long seed, boolean setupDone, BlockPos origin, net.minecraft.world.level.block.Rotation rotation,
		BlockPos spawn, float spawnYaw, float spawnPitch) {}

	private WorldSelfTest(T3CraftClient mod, String place) {
		this.mod = mod;
		this.place = place;
		this.life = System.getProperty("t3craft.worldlife") == null ? null : new AgentLifeShots(mod);
	}

	static WorldSelfTest fromSystemProperty(T3CraftClient mod) {
		String name = System.getProperty("t3craft.worldtest");
		return name == null || name.isBlank() ? null : new WorldSelfTest(mod, name.trim());
	}

	void tick(Minecraft minecraft) {
		if (step == Step.DONE) return;
		if (--deadline == 0) { finish(minecraft, "FAIL timed out at " + step); return; }
		Screen screen = minecraft.gui.screen();
		try {
			switch (step) {
				case TITLE -> {
					if (!(screen instanceof TitleScreen) || ++wait < 80) return;
					shot(minecraft, "title");
					press(screen, "T3 World");
					step = Step.FORM; wait = 0;
				}
				case FORM -> {
					if (screen instanceof T3WorldHubScreen) {
						if (++wait < 30) return;
						shot(minecraft, "hub");
						press(screen, "New T3 World"); wait = 0; return;
					}
					if (!(screen instanceof T3WorldScreen form) || ++wait < 30) return;
					if (!form.placeForTest().label().equalsIgnoreCase(place)) {
						if (!form.selectPlaceForTest(place)) { finish(minecraft, "FAIL no place called " + place); return; }
						wait = 10; return;
					}
					T3CraftClient.LOGGER.info("WORLDTEST form: place {} creative {} name '{}'", form.placeForTest().label(), form.creativeForTest(), form.nameForTest());
					shot(minecraft, "new-world");
					press(screen, "Create T3 World");
					step = Step.ARRIVE; wait = 0;
				}
				case ARRIVE -> {
					if (minecraft.level == null || minecraft.player == null || screen != null) { wait = 0; return; }
					if (++wait < 50) return;
					var player = minecraft.player;
					if (arrivedAt == null) {
						arrivedAt = player.blockPosition();
						arrivedYaw = player.getYRot();
						arrivedPitch = player.getXRot();
						T3CraftClient.LOGGER.info("WORLDTEST arrived at {} yaw {} pitch {}", arrivedAt.toShortString(), arrivedYaw, arrivedPitch);
						shot(minecraft, "arrival");
					}
					if (!readMarker(minecraft)) return;
					if (marker == null || marker.origin() == null) { finish(minecraft, "FAIL no office in the T3 World data"); return; }
					frame = new OfficeFrame(marker.origin(), marker.rotation());
					step = Step.OUTSIDE; wait = 0;
				}
				case OUTSIDE -> {
					// Two overviews from the air, the way a player with cheats would: over the plaza looking at the door,
					// then from the view side looking back at the glazing. Shot right after each teleport, before gravity matters.
					int[] e = OfficeDesign.EXTENT;
					int[] door = OfficeDesign.doorOutside();
					if (++wait == 1) hud(minecraft, false);
					if (wait == 20) look(minecraft, door[0] + 0.5, 7, e[2] - 22, 0, 12);
					if (wait == 32) shot(minecraft, "plaza");
					if (wait == 60) look(minecraft, (e[0] + e[1]) / 2.0 + 0.5, 8, e[3] + 30, 180, 10);
					if (wait == 72) shot(minecraft, "lake");
					if (wait < 90) return;
					step = Step.DESKS; wait = 0;
				}
				case DESKS -> {
					// Ground-floor studio: stand in the room behind the desk bench and watch the agents arrive and sit.
					if (++wait == 1 || wait % 20 == 0) look(minecraft, 6.5, 0.6, 11.5, 180, 16);
					boolean paired = mod.state().snapshot().threads().stream().anyMatch(r -> r.status() == T3State.Status.WORKING);
					int settled = settledAtDesks();
					if (wait < 100 || (paired && settled < workers() && wait < 20 * 45)) return;
					shot(minecraft, "desks");
					step = Step.BOARD; wait = 0;
				}
				case BOARD -> {
					// The usage limits board, from the room six blocks in front of it: by day, then at night (glow frames stay lit).
					var anchor = UsageBoardLayout.ANCHOR.getAsJsonArray("topLeft");
					double x = anchor.get(0).getAsInt() + .5 - 5.5, z = anchor.get(2).getAsInt() + UsageBoardLayout.COLUMNS / 2.0;
					if (++wait == 1) { hud(minecraft, false); minecraft.player.connection.sendCommand("time set 1000"); }
					if (wait == 2 || wait == 60) look(minecraft, x, 0, z, -90, -8);
					if (wait == 40) shot(minecraft, "board-day");
					if (wait == 50) minecraft.player.connection.sendCommand("time set 18000");
					// Lamps follow the night on the office's 100-tick decoration timer.
					if (wait == 200) shot(minecraft, "board-night");
					if (wait < 210) return;
					minecraft.player.connection.sendCommand("time set 1000");
					boardFrames = mod.residents().usageBoardForTest().framesForTest().size();
					var shown = mod.residents().usageBoardForTest().shownForTest();
					boardShows = shown == null ? "connect T3" : shown.providers().isEmpty() ? String.valueOf(shown.title()) + ": " + shown.detail() : shown.providers().stream().map(UsageBoardData.Provider::name).toList().toString();
					step = talk == null ? Step.LIFE : Step.TALK; wait = 0;
				}
				case TALK -> {
					if (talk()) return;
					step = Step.LIFE; wait = 0;
				}
				case LIFE -> {
					// -Pworldlife: frame sequences of the characters (typing, glancing up, name tags, idles).
					if (life != null && life.tick(minecraft, frame)) return;
					step = Step.LOOK; wait = 0;
				}
				case LOOK -> {
					if (++wait < 30 || !readMarker(minecraft)) return;
					hud(minecraft, true);
					Marker data = marker;
					if (data == null || !data.setupDone()) { finish(minecraft, "FAIL no T3 World data on the integrated server"); return; }
					boolean onPlaza = data.spawn() != null && arrivedAt != null && arrivedAt.distManhattan(data.spawn()) <= 2;
					int workers = workers(), seated = settledAtDesks();
					boolean paired = !mod.state().snapshot().threads().isEmpty();
					boolean desks = !paired || (workers > 0 && seated == workers);
					// Paired, the board must show the fixture's providers (server.getConfig in the real T3's shape), not a placeholder.
					var shown = mod.residents().usageBoardForTest().shownForTest();
					boolean board = boardFrames == UsageBoardLayout.COLUMNS * UsageBoardLayout.ROWS && (!paired || shown != null && !shown.providers().isEmpty());
					finish(minecraft, (onPlaza && desks && board ? "PASS" : "FAIL") + " T3 World '" + data.name() + "' place " + place + " seed " + data.seed()
						+ " office " + data.origin().toShortString() + " rotation " + data.rotation() + " spawn " + data.spawn().toShortString()
						+ " yaw " + data.spawnYaw() + " pitch " + data.spawnPitch() + " arrived " + arrivedAt.toShortString()
						+ " usage board frames " + boardFrames + " showing " + boardShows + " agents " + mod.residents().brainForTest().agents().size() + " working " + workers + " at desks " + seated + (paired ? "" : " (unpaired)"));
				}
				default -> {}
			}
		} catch (RuntimeException e) {
			T3CraftClient.LOGGER.error("WORLDTEST error", e);
			finish(minecraft, "FAIL " + e);
		}
	}

	/** One conversation, start to finish: framed, photographed at each stage, checked in the world. True while running. */
	private boolean talk() {
		Minecraft minecraft = Minecraft.getInstance();
		var talks = mod.residents().talks();
		var brain = mod.residents().brainForTest();
		wait++;
		if (conversation == null) {
			if ("stage".equals(talk) && !staged) {
				var parent = brain.agents().values().stream().filter(OfficeBrain.Agent::settled)
					.min(java.util.Comparator.comparing((OfficeBrain.Agent a) -> a.zone != OfficeBrain.Zone.WORK)).orElse(null);
				if (parent == null) {
					if (wait > 20 * 60) { finish(minecraft, "FAIL no seated agent to stage a talk with"); return false; }
					return true;
				}
				staged = true;
				var raw = new com.google.gson.JsonObject();
				raw.addProperty("parentThreadId", parent.threadId);
				raw.addProperty("relationshipToParent", "subagent");
				var child = new T3State.ThreadRow("staged-helper", "Staged: the greeting tests", "Staged", null, "staged", T3State.Status.WORKING, null, null, raw);
				talks.director().stage(AgentTalk.talk(AgentTalk.Kind.DELEGATE, parent.threadId, child));
			}
			String parentTitle = talk.contains(":") ? talk.substring(talk.indexOf(':') + 1) : null;
			var live = talks.director().conversations().stream().filter(c -> c.phase == AgentTalk.Phase.APPROACH).filter(c -> parentTitle == null
				|| c.parent.title != null && c.parent.title.contains(parentTitle)).toList();
			if (!live.isEmpty()) {
				conversation = live.getFirst();
				wait = 0;
				T3CraftClient.LOGGER.info("WORLDTEST talk {} parent {} floor {} '{}' / '{}' rotation {}", conversation.talk.kind(), conversation.talk.parentId(),
					conversation.parent.floor, conversation.talk.first(), conversation.talk.second(), frame.rotation());
				hud(minecraft, true); // name tags ("Helper") show with the HUD
				minecraft.gui.hud.getChat().clearMessages(false);
				frameTalk(minecraft);
				return true;
			}
			if (wait % 200 == 0) {
				var snapshot = mod.state().snapshot();
				var parent = brain.agents().values().stream().filter(x -> parentTitle == null || x.title != null && x.title.contains(parentTitle)).findFirst().orElse(null);
				String kids = snapshot.children().stream().filter(r -> parent != null && parent.threadId.equals(AgentTalk.subagentParent(r.raw())))
					.map(r -> r.title() + "=" + r.status()).toList().toString();
				T3CraftClient.LOGGER.info("WORLDTEST talk waiting {} s: parent {} ({} {}, settled {}), {} children in snapshot, its children {}, queued {} active {}",
					wait / 20, parent == null ? "not in the office" : parent.threadId, parent == null ? "-" : parent.status, parent == null ? "-" : parent.zone,
					parent != null && parent.settled(), snapshot.children().size(), kids, talks.director().limiter().queued(), talks.director().limiter().active());
			}
			int limit = talked > 0 ? 20 * 120 : "stage".equals(talk) ? 20 * 90 : 20 * Integer.parseInt(talk.split(":", 2)[0].trim());
			if (wait > limit) {
				if (talked > 0) { T3CraftClient.LOGGER.info("WORLDTEST talk no second conversation within {} s", limit / 20); return false; }
				finish(minecraft, "FAIL no conversation within " + limit / 20 + " s"); return false;
			}
			return true;
		}
		var c = conversation;
		if (c.phase != lastPhase) {
			T3CraftClient.LOGGER.info("WORLDTEST talk phase {} after {} ticks (parent arrived {}, helper arrived {})", c.phase, wait, c.parent.arrived(), c.helper.arrived());
			lastPhase = c.phase;
		}
		var helper = talks.helperFor(c);
		if (helper != null && c.phase != AgentTalk.Phase.DONE) {
			double off = helper.position().distanceTo(frame.point(c.helper.x, c.helper.y(), c.helper.z));
			if (off > 0.3) { finish(minecraft, "FAIL helper is " + off + " blocks off its brain position"); return false; }
			helperSeen = true;
			// Legs swing while walking (vanilla's zero-movement animation would keep them still).
			if (c.helper.walking && helper.walkAnimation.speed() > 0.2f) helperLegs = true;
		}
		var walker = mod.residents().mannequinFor(c.parent.threadId);
		if (walker != null && c.parent.walking && walker.walkAnimation.speed() > 0.2f) parentLegs = true;
		if (c.speaker() != null && c.ticks > 2) {
			var bubble = talks.bubbleFor(c);
			var speaker = c.speaker() == c.helper ? helper : mod.residents().mannequinFor(c.parent.threadId);
			if (bubble == null || speaker == null || Math.abs(bubble.getX() - speaker.getX()) > 0.01 || bubble.getY() <= speaker.getY() + 2) {
				finish(minecraft, "FAIL no speech bubble over the " + (c.speaker() == c.helper ? "helper" : "parent")); return false;
			}
			bubbleSeen = true;
		}
		String stage = switch (c.phase) {
			case APPROACH -> c.parent.arrived() && !c.helper.arrived() ? "approach" : null;
			case FIRST -> c.ticks == 30 ? "first-line" : null;
			case SECOND -> c.ticks == 30 ? "second-line" : null;
			case LEAVE -> c.ticks == 40 ? "leaving" : null;
			default -> null;
		};
		String prefix = talked == 0 ? "talk-" : "talk" + (talked + 1) + "-";
		if (stage != null && talkShots.add(prefix + stage)) { minecraft.gui.hud.getChat().clearMessages(false); shot(minecraft, prefix + stage); }
		if (c.phase != AgentTalk.Phase.DONE && talks.director().conversations().contains(c)) {
			if (wait > 20 * 90) { finish(minecraft, "FAIL conversation stuck in " + c.phase); return false; }
			return true;
		}
		// Back at the desk, in the seat's pose.
		var parent = brain.agents().get(c.talk.parentId());
		var mannequin = parent == null ? null : mod.residents().mannequinFor(parent.threadId);
		if (parent == null || !parent.settled() || mannequin == null) return wait < 20 * 120 || fail(minecraft, "FAIL the parent never sat back down");
		double off = mannequin.position().distanceTo(frame.point(parent.goal.x() + 0.5, parent.y(), parent.goal.z() + 0.5));
		if (off > 0.6) return wait < 20 * 120 || fail(minecraft, "FAIL the parent is " + off + " blocks from its spot");
		if (!helperSeen || !bubbleSeen || !helperLegs || !parentLegs || talks.director().conversations().contains(c))
			return fail(minecraft, "FAIL helper " + helperSeen + " bubble " + bubbleSeen + " helper legs " + helperLegs + " parent legs " + parentLegs);
		shot(minecraft, prefix + "back-at-desk");
		T3CraftClient.LOGGER.info("WORLDTEST talk PASS {} rotation {}: helper walked in and out with a walk animation, both lines in bubbles, parent back in its seat", c.talk.kind(), frame.rotation());
		hud(minecraft, true);
		talked++;
		if (talked >= 2 || "stage".equals(talk)) return false;
		conversation = null; helperSeen = false; bubbleSeen = false; helperLegs = false; parentLegs = false; lastPhase = null; wait = 0;
		return true;
	}

	private boolean fail(Minecraft minecraft, String result) {
		finish(minecraft, result);
		return false;
	}

	/** Stands the camera to one side of the meeting, looking at the pair at head height. */
	private void frameTalk(Minecraft minecraft) {
		var c = conversation;
		double[] a = c.route.parentMeet(), b = c.route.helperMeet();
		double mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2, ax = b[0] - a[0], az = b[1] - a[1], len = Math.max(1e-6, Math.hypot(ax, az));
		double px = -az / len, pz = ax / len;
		boolean[][] blocked = T3Office.blocked();
		double[] camera = null;
		// Side-on, so the two stand apart in the frame; the spot with the least furniture between camera and pair wins.
		int best = Integer.MAX_VALUE;
		for (double d : new double[] {4.5, -4.5, 4, -4, 5, -5, 3.5, -3.5, 5.5, -5.5, 3, -3}) {
			double cx = mx + px * d, cz = mz + pz * d;
			int x0 = (int) Math.floor(cx), z0 = (int) Math.floor(cz);
			if (x0 < 0 || z0 < 0 || x0 >= T3Office.WIDTH || z0 >= T3Office.LENGTH || blocked[x0][z0]) continue;
			int inTheWay = 0;
			for (int k = 1; k <= 12; k++) {
				double t = k / 12.0 * 0.8;
				int x = (int) Math.floor(cx + (mx - cx) * t), z = (int) Math.floor(cz + (mz - cz) * t);
				if (x < 0 || z < 0 || x >= T3Office.WIDTH || z >= T3Office.LENGTH || blocked[x][z]) inTheWay++;
			}
			if (inTheWay < best) { best = inTheWay; camera = new double[] {cx, cz}; }
		}
		if (camera == null) camera = new double[] {mx - ax / len * 3.5, mz - az / len * 3.5};
		double floorY = c.parent.floor * T3Office.FLOOR_HEIGHT;
		// Hover a little above eye height (creative flight), looking slightly down at the pair and the bubble above them.
		minecraft.player.getAbilities().flying = true;
		Vec3 eye = frame.point(camera[0], floorY + 0.9, camera[1]), target = frame.point(mx, floorY + 1.6, mz);
		minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f", eye.x, eye.y, eye.z, target.x, target.y, target.z));
	}

	/**
	 * Copies the marker on the server thread; true once a fresh copy is in {@link #marker} (null for no marker).
	 * Called again on later ticks until the copy has arrived, so the client thread never touches SavedData.
	 */
	private boolean readMarker(Minecraft minecraft) {
		if (reading == null) {
			var server = minecraft.getSingleplayerServer();
			if (server == null) { marker = null; return true; }
			reading = server.submit(() -> {
				T3WorldData d = T3WorldData.get(server);
				return d == null ? null : new Marker(d.name, d.seed, d.setupDone, d.origin, d.rotation, d.spawn, d.spawnYaw, d.spawnPitch);
			});
			return false;
		}
		if (!reading.isDone()) return false;
		marker = reading.join();
		reading = null;
		return true;
	}

	private int workers() {
		return (int) mod.residents().brainForTest().agents().values().stream().filter(a -> a.zone == OfficeBrain.Zone.WORK).count();
	}

	/** Working agents that have stopped walking and whose Mannequin stands at the desk's world position. */
	private int settledAtDesks() {
		int count = 0;
		for (OfficeBrain.Agent agent : mod.residents().brainForTest().agents().values()) {
			if (agent.zone != OfficeBrain.Zone.WORK || !agent.settled()) continue;
			var mannequin = mod.residents().mannequinFor(agent.threadId);
			if (mannequin == null) continue;
			Vec3 expected = frame.point(agent.goal.x() + 0.5, agent.y(), agent.goal.z() + 0.5);
			double off = mannequin.position().distanceTo(expected);
			if (off < 0.6) count++;
			else T3CraftClient.LOGGER.warn("WORLDTEST agent {} is {} blocks from its desk ({} vs {})", agent.threadId, String.format(Locale.ROOT, "%.2f", off), mannequin.position(), expected);
		}
		return count;
	}

	/** Teleports the player to a design-relative spot with a design-relative look, both turned with the office. */
	private void look(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
		Vec3 at = frame.point(x, y, z);
		minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", at.x, at.y, at.z, frame.yaw(yaw), pitch));
	}

	/** Shows or hides the HUD (F1) for scenery shots. */
	private static void hud(Minecraft minecraft, boolean shown) {
		if (minecraft.gui.hud.isHidden() == shown) minecraft.gui.hud.toggle();
	}

	private static void press(Screen screen, String label) {
		Button button = screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(label))
			.map(c -> (Button) c).findFirst().orElseThrow(() -> new IllegalStateException("no button " + label + " on " + screen.getClass().getSimpleName()));
		// A real click at the button's centre, the same path a mouse takes.
		screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0,
			new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
	}

	private void shot(Minecraft minecraft, String label) {
		String file = "t3world-" + place.toLowerCase(Locale.ROOT).replace(' ', '-') + "-" + label + ".png";
		Screenshot.grab(minecraft.gameDirectory, file, minecraft.gameRenderer.mainRenderTarget(), 1,
			message -> T3CraftClient.LOGGER.info("WORLDTEST shot {} {}", label, message.getString()));
	}

	private void finish(Minecraft minecraft, String result) {
		step = Step.DONE;
		hud(minecraft, true);
		T3CraftClient.LOGGER.info("WORLDTEST RESULT {}", result);
		minecraft.execute(minecraft::stop);
	}
}
