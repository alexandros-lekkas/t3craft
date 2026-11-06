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
	private enum Step { TITLE, FORM, ARRIVE, OUTSIDE, DESKS, BOARD, LOOK, DONE }

	private final T3CraftClient mod;
	private final String place;
	private Step step = Step.TITLE;
	private int wait, deadline = 20 * 60 * 20;
	private BlockPos arrivedAt;
	private float arrivedYaw, arrivedPitch;
	private OfficeFrame frame;
	private int boardFrames;
	/** The marker as last copied on the server thread (SavedData belongs to it), and a copy on its way. */
	private Marker marker;
	private java.util.concurrent.CompletableFuture<Marker> reading;

	private record Marker(String name, long seed, boolean setupDone, BlockPos origin, net.minecraft.world.level.block.Rotation rotation,
		BlockPos spawn, float spawnYaw, float spawnPitch) {}

	private WorldSelfTest(T3CraftClient mod, String place) {
		this.mod = mod;
		this.place = place;
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
					boolean board = boardFrames == UsageBoardLayout.COLUMNS * UsageBoardLayout.ROWS;
					finish(minecraft, (onPlaza && desks && board ? "PASS" : "FAIL") + " T3 World '" + data.name() + "' place " + place + " seed " + data.seed()
						+ " office " + data.origin().toShortString() + " rotation " + data.rotation() + " spawn " + data.spawn().toShortString()
						+ " yaw " + data.spawnYaw() + " pitch " + data.spawnPitch() + " arrived " + arrivedAt.toShortString()
						+ " usage board frames " + boardFrames + " agents " + mod.residents().brainForTest().agents().size() + " working " + workers + " at desks " + seated + (paired ? "" : " (unpaired)"));
				}
				default -> {}
			}
		} catch (RuntimeException e) {
			T3CraftClient.LOGGER.error("WORLDTEST error", e);
			finish(minecraft, "FAIL " + e);
		}
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
