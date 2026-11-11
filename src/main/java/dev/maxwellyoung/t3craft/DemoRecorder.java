package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dev-only launch-video recorder, enabled with {@code -Dt3craft.demo=<output dir>} ({@code ./gradlew runClient -Pdemo=…},
 * normally through {@code scripts/demo/record.sh}). It creates a T3 World from the title screen, then plays a scripted
 * scene against the office fixture (positions come from the office's own anchors and the agents, and a beat that
 * does not happen ends the run with {@code DEMO RESULT FAIL} and the reason) and records each shot from the game's own framebuffer into
 * {@code <output dir>/seg-<name>.mp4} through an ffmpeg child process. Nothing depends on the window, the mouse or
 * the keyboard:
 * <ul>
 * <li>the cinematic camera is a client-only marker entity moved every tick, at a steady speed that carries through
 * each cut, so vanilla's own interpolation makes the motion smooth at any frame rate; the first-person shots are the
 * survival player itself, turned and walked by the script;</li>
 * <li>while it runs the game clock advances by exactly 1/60 s per rendered frame ({@code DemoClockMixin}), so the
 * video is steady however long a frame takes to capture.</li>
 * </ul>
 * {@code marks.txt} in the output directory notes the frame of each beat inside its segment for the edit.
 */
public final class DemoRecorder {
	private static final int FPS = 60;
	private static volatile DemoRecorder instance;

	/** The game clock for this frame; real time unless a recording is running. Called once per frame by the mixin. */
	public static long clock(long realMs) {
		DemoRecorder recorder = instance;
		return recorder == null ? realMs : recorder.frame(realMs);
	}

	private enum Step { TITLE, FORM, ARRIVE, SETTLE, HOOK, SURVIVAL, ARC, CARD, FOLLOW, TALK_WAIT, TALK, TYPE, DUSK, END_WAIT, ENDCARD, FINISH, DONE }

	private record Frame(Segment segment, NativeImage image) {}
	private record Marker(BlockPos origin, net.minecraft.world.level.block.Rotation rotation) {}
	/** Where the survival beat plays: the player's feet and the foot of the tree it chops, on level ground outside the door. */
	private record Stage(Vec3 feet, BlockPos tree) {}

	private final T3CraftClient mod;
	private final Path out;
	private final String place = System.getProperty("t3craft.demoPlace", "Lakeside");
	private final String ffmpeg = System.getProperty("t3craft.demoFfmpeg", "ffmpeg");
	/** The fixture thread that asks for approval mid-video, and the finished one the player types to. */
	private final String staged = System.getProperty("t3craft.demoThread", "office-4");
	private final String typedTo = System.getProperty("t3craft.demoTypeThread", "office-5");
	private static final String TYPED = "ship it";
	private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(6);
	private final List<Segment> segments = new ArrayList<>();
	private Step step = Step.TITLE;
	private int t, deadline = 20 * 60 * 14;
	private OfficeFrame frame;
	private java.util.concurrent.CompletableFuture<Marker> reading;

	// Frame clock and capture (render thread).
	private boolean fixed;
	private double virtualMs = -1;
	private volatile Segment segment;

	// Camera: a marker entity in the cinematic shots, the survival player itself in the first-person ones.
	private Entity cam;
	private Vec3 eye = Vec3.ZERO;
	private float yaw, pitch;
	private boolean cinematic, ride;

	// Scene state.
	private OfficeBrain.Agent waiting, typist;
	private Vec3 hookFrom, hookAlong, hookRow;
	private Stage stage;
	private int pingAt, turnAt, logAt;
	private Vec3 walkFrom;
	private double[] stand;
	private double standAngle, arcSign;
	private float aimYaw, aimPitch;
	private Vec3 followEye, followDir;
	private int seatedAt, captionAt;
	private AgentTalk.Conversation conversation;
	private AgentTalk.Phase lastPhase;
	private double[] talkMid;
	private double talkAngle, talkFloor;
	private int talkSide = 1, firstAt;
	private int secondAt, typedAt, sentAt, workingAt, nextKey, approvedAt, flipAt, closedAt, followAt;
	private boolean doneSeen;

	private DemoRecorder(T3CraftClient mod, Path out) {
		this.mod = mod;
		this.out = out;
	}

	static DemoRecorder fromSystemProperty(T3CraftClient mod) {
		String dir = System.getProperty("t3craft.demo");
		if (dir == null || dir.isBlank()) return null;
		DemoRecorder recorder = new DemoRecorder(mod, Path.of(dir).toAbsolutePath());
		try {
			Files.createDirectories(recorder.out);
			Files.deleteIfExists(recorder.out.resolve("marks.txt"));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		Thread writer = new Thread(recorder::write, "t3craft-demo-writer");
		writer.setDaemon(true);
		writer.start();
		instance = recorder;
		return recorder;
	}

	// ---- Frames ----

	private long frame(long realMs) {
		Minecraft mc = Minecraft.getInstance();
		Segment s = segment;
		// The main target still holds the frame rendered after the last tick's camera update.
		if (s != null) {
			s.requested++;
			s.pending.incrementAndGet();
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
				try { queue.put(new Frame(s, image)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				s.pending.decrementAndGet();
			});
		}
		for (Segment closing : segments) {
			if (closing.ended && !closing.closeQueued && closing.pending.get() == 0) {
				closing.closeQueued = true;
				try { queue.put(new Frame(closing, null)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			}
		}
		if (!fixed) return realMs;
		if (virtualMs < 0) virtualMs = realMs;
		virtualMs += 1000.0 / FPS;
		return Math.round(virtualMs);
	}

	private void write() {
		try {
			while (true) {
				Frame f = queue.take();
				if (f.image == null) { f.segment.finish(); continue; }
				try { f.segment.write(f.image); } finally { f.image.close(); }
			}
		} catch (Exception e) {
			T3CraftClient.LOGGER.error("DEMO writer stopped", e);
		}
	}

	/** One shot: an ffmpeg child fed raw RGBA frames, started when the first frame tells us the size. */
	private final class Segment {
		final String name;
		final AtomicInteger pending = new AtomicInteger();
		int requested;
		volatile boolean ended, closeQueued, done;
		private Process process;
		private WritableByteChannel channel;

		Segment(String name) { this.name = name; }

		void write(NativeImage image) throws IOException {
			int w = image.getWidth(), h = image.getHeight();
			if (process == null) {
				File log = out.resolve("seg-" + name + ".log").toFile();
				process = new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", String.valueOf(FPS),
					"-i", "-", "-an", "-vf", "scale=out_color_matrix=bt709:out_range=tv", "-c:v", "libx264", "-preset", "veryfast", "-crf", "12", "-pix_fmt", "yuv420p",
					"-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", out.resolve("seg-" + name + ".mp4").toString())
					.redirectErrorStream(true).redirectOutput(log).start();
				channel = Channels.newChannel(process.getOutputStream());
				T3CraftClient.LOGGER.info("DEMO segment {} recording at {}x{}", name, w, h);
			}
			ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memByteBuffer(image.getPointer(), w * h * 4);
			while (pixels.hasRemaining()) channel.write(pixels);
		}

		void finish() throws IOException, InterruptedException {
			if (process != null) {
				channel.close();
				int code = process.waitFor();
				T3CraftClient.LOGGER.info("DEMO segment {} closed: {} frames, ffmpeg exit {}", name, requested, code);
			}
			done = true;
		}
	}

	private void begin(String name) {
		Segment s = new Segment(name);
		segments.add(s);
		segment = s;
		mark("start");
	}

	private void end() {
		Segment s = segment;
		if (s == null) return;
		mark("end");
		segment = null;
		s.ended = true;
	}

	/** Notes the current frame of the open segment under {@code label}, for the edit. */
	private void mark(String label) {
		Segment s = segment;
		if (s == null) return;
		String line = s.name + " " + label + " " + s.requested + "\n";
		T3CraftClient.LOGGER.info("DEMO mark {}", line.strip());
		try {
			Files.writeString(out.resolve("marks.txt"), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			T3CraftClient.LOGGER.warn("DEMO could not write marks", e);
		}
	}

	// ---- Camera ----

	/** Design-relative point, turned with the office. */
	private Vec3 d(double x, double y, double z) { return frame.point(x, y, z); }

	private static double ease(double k) {
		k = Mth.clamp(k, 0, 1);
		return k * k * k * (k * (k * 6 - 15) + 10);
	}

	private static Vec3 mix(Vec3 a, Vec3 b, double k) { return a.add(b.subtract(a).scale(k)); }

	private static float yawTo(Vec3 from, Vec3 at) { return (float) Math.toDegrees(Math.atan2(at.z - from.z, at.x - from.x)) - 90; }

	private static float pitchTo(Vec3 from, Vec3 at) { return (float) -Math.toDegrees(Math.atan2(at.y - from.y, Math.hypot(at.x - from.x, at.z - from.z))); }

	/** Moves the camera to look from {@code from} at {@code at}; a cut jumps, otherwise the frame interpolates from the last tick. */
	private void camera(Minecraft mc, Vec3 from, Vec3 at, boolean cut) {
		if (cam == null || cam.isRemoved() || cam.level() != mc.level) {
			cam = net.minecraft.world.entity.EntityTypes.MARKER.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			cam.setId(-7900001);
			mc.level.addEntity(cam);
			cut = true;
		}
		float newYaw = yawTo(from, at), newPitch = pitchTo(from, at);
		newYaw = cut ? Mth.wrapDegrees(newYaw) : yaw + Mth.wrapDegrees(newYaw - yaw);
		Vec3 old = cut ? from : eye;
		float oldYaw = cut ? newYaw : yaw, oldPitch = cut ? newPitch : pitch;
		cam.setPos(from.x, from.y, from.z);
		cam.setYRot(newYaw);
		cam.setXRot(newPitch);
		cam.xo = cam.xOld = old.x; cam.yo = cam.yOld = old.y; cam.zo = cam.zOld = old.z;
		cam.yRotO = oldYaw; cam.xRotO = oldPitch;
		eye = from; yaw = newYaw; pitch = newPitch;
		if (mc.getCameraEntity() != cam && cinematic) mc.setCameraEntity(cam);
	}

	/**
	 * A cinematic shot: the player becomes an (invisible) spectator and the view moves to the marker camera. The HUD
	 * stays on so name tags and toasts draw; with a non-player camera vanilla draws no hotbar, hearts or hand.
	 * {@code ride} carries the player along with the camera, which makes a nearby agent look at the lens.
	 */
	private void cinematic(Minecraft mc, boolean ride, int fov) {
		if (!cinematic) command(mc, "gamemode spectator " + name(mc));
		cinematic = true;
		this.ride = ride;
		mc.player.input = new net.minecraft.client.player.KeyboardInput(mc.options);
		if (cam != null) mc.setCameraEntity(cam);
		hud(mc, true);
		gui(mc, 5);
		mc.options.fov().set(fov);
	}

	/** A first-person shot: a survival player standing at {@code feet}, looking along {@code yaw}/{@code pitch}. */
	private void firstPerson(Minecraft mc, Vec3 feet, float yaw, float pitch, int guiScale) {
		cinematic = ride = false;
		clearCaption(mc);
		command(mc, "gamemode survival " + name(mc));
		command(mc, String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f %.2f %.2f", name(mc), feet.x, feet.y, feet.z, Mth.wrapDegrees(yaw), pitch));
		mc.setCameraEntity(mc.player);
		mc.player.setDeltaMovement(Vec3.ZERO);
		hud(mc, true);
		gui(mc, guiScale);
		mc.options.fov().set(70);
		mc.gui.hud.getChat().clearMessages(false);
	}

	/** Turns the player's head; vanilla interpolates from last tick's angles, so a per-tick call reads as a smooth turn. */
	private static void look(Minecraft mc, float yaw, float pitch) {
		var p = mc.player;
		float y = p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot());
		p.setYRot(y);
		p.setXRot(Mth.clamp(pitch, -89, 89));
		p.setYHeadRot(y);
	}

	/** The small drift of a hand-held view, so a first-person shot never freezes. */
	private float swayYaw() { return (float) (0.8 * Math.sin(t * 0.11) + 0.3 * Math.sin(t * 0.23 + 1)); }
	private float swayPitch() { return (float) (0.45 * Math.sin(t * 0.077 + 1.3) + 0.2 * Math.sin(t * 0.19)); }

	private static void swing(Minecraft mc) {
		var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
		mc.player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
	}

	private static String name(Minecraft mc) { return mc.player.getName().getString(); }

	// ---- Script ----

	void tick(Minecraft mc) {
		if (step == Step.DONE) return;
		if (--deadline == 0) { finish(mc, "FAIL timed out at " + step); return; }
		try {
			t++;
			script(mc);
			// Command feedback ("Your game mode has been updated") never belongs in a frame.
			if (fixed && mc.player != null) mc.gui.hud.getChat().clearMessages(false);
			if (cinematic && step != Step.DONE && mc.player != null) {
				if (ride) {
					mc.player.setDeltaMovement(Vec3.ZERO);
					mc.player.setPos(eye.x, eye.y - mc.player.getEyeHeight(), eye.z);
				}
				// The spectator's head may sit in a wall; vanilla resets this flag at the start of every tick, so setting it
				// after the tick only keeps the "inside a block" overlay out of the frames.
				mc.player.noPhysics = true;
			}
		} catch (RuntimeException e) {
			T3CraftClient.LOGGER.error("DEMO error", e);
			finish(mc, "FAIL " + e);
		}
	}

	private void go(Step next) {
		T3CraftClient.LOGGER.info("DEMO step {} -> {}", step, next);
		step = next;
		t = 0;
	}

	private void script(Minecraft mc) {
		Screen screen = mc.gui.screen();
		int[] e = OfficeDesign.EXTENT;
		int[] door = OfficeDesign.doorOutside();
		double midX = (e[0] + e[1]) / 2.0 + 0.5, midZ = (e[2] + e[3]) / 2.0 + 0.5;
		switch (step) {
			case TITLE -> {
				if (!(screen instanceof TitleScreen) || t < 80) return;
				press(screen, "T3 World");
				go(Step.FORM);
			}
			case FORM -> {
				if (screen instanceof T3WorldHubScreen) {
					if (t < 30) return;
					press(screen, "New T3 World"); t = 0; return;
				}
				if (!(screen instanceof T3WorldScreen form) || t < 30) return;
				if (!form.placeForTest().label().equalsIgnoreCase(place)) {
					if (!form.selectPlaceForTest(place)) { finish(mc, "FAIL no place called " + place); return; }
					t = 10; return;
				}
				press(screen, "Create T3 World");
				go(Step.ARRIVE);
			}
			case ARRIVE -> {
				if (mc.level == null || mc.player == null || screen != null) { t = 0; return; }
				if (t < 50) return;
				if (reading == null) {
					var server = mc.getSingleplayerServer();
					reading = server.submit(() -> {
						T3WorldData data = T3WorldData.get(server);
						return data == null || data.origin == null ? null : new Marker(data.origin, data.rotation);
					});
					return;
				}
				if (!reading.isDone()) return;
				Marker marker = reading.join();
				if (marker == null) { finish(mc, "FAIL no office in the T3 World data"); return; }
				frame = new OfficeFrame(marker.origin(), marker.rotation());
				go(Step.SETTLE);
			}
			case SETTLE -> {
				if (t == 1) {
					mc.options.pauseOnLostFocus = false;
					mc.options.bobView().set(true);
					mc.options.autoJump().set(true);
					String who = name(mc);
					// The survival kit goes in now, with every recipe known, so its advancement and recipe toasts are long gone by the first shot.
					for (String command : List.of("difficulty peaceful", "weather clear", "time set 10500", "recipe give " + who + " *",
						"item replace entity " + who + " hotbar.0 with stone_axe", "item replace entity " + who + " hotbar.1 with iron_pickaxe",
						"item replace entity " + who + " hotbar.2 with iron_sword", "item replace entity " + who + " hotbar.3 with torch 32",
						"item replace entity " + who + " hotbar.4 with cooked_beef 12", "item replace entity " + who + " hotbar.5 with oak_planks 41",
						"item replace entity " + who + " hotbar.6 with cobblestone 64", "item replace entity " + who + " hotbar.7 with water_bucket",
						"item replace entity " + who + " hotbar.8 with bread 5")) command(mc, command);
					cinematic(mc, false, 60);
					// The corner status follows the thread that will ask for the approval, and its ping is one the player gets.
					mod.focus(staged);
					fixed = true;
				}
				var agents = mod.residents().brainForTest().agents();
				waiting = agents.get(staged);
				typist = agents.get(typedTo);
				camera(mc, d(midX, 2.6, midZ - 2), d(midX - 6, 1.2, midZ - 6), t == 1);
				boolean seated = !agents.isEmpty() && agents.values().stream().allMatch(OfficeBrain.Agent::settled);
				if (t < 300 || (!seated && t < 20 * 75)) return;
				if (waiting == null || waiting.status != T3State.Status.WORKING || waiting.zone != OfficeBrain.Zone.WORK)
					{ finish(mc, "FAIL the staged thread " + staged + " is not working at a desk; is the fixture running with --demo?"); return; }
				if (typist == null || typist.status != T3State.Status.DONE) { finish(mc, "FAIL the thread to type to (" + typedTo + ") is not a finished agent"); return; }
				if (!frameHook(agents.values())) { finish(mc, "FAIL fewer than two agents are working side by side at the desks for the opening shot"); return; }
				T3CraftClient.LOGGER.info("DEMO office {} rotation {}, {} agents, all seated {}", frame.origin().toShortString(), frame.rotation(), agents.size(), seated);
				go(Step.HOOK);
			}
			case HOOK -> {
				// Over the bench, tracking along a row of agents typing at their laptops: faces, hands and screens from the first frame.
				int length = 50;
				if (t == 1) cinematic(mc, false, 60);
				double s = (t - 6) / (double) length * 3.0;
				Vec3 from = hookFrom.add(hookAlong.scale(s));
				camera(mc, from, hookRow.add(hookAlong.scale(s + 2.1)), t == 1);
				if (t == 4) caption(mc, 46, 0.09f, 2, false, big("Your coding agents"), big("live in Minecraft now."));
				if (t == 5) mc.gui.toastManager().clear();
				if (t == 6) begin("hook");
				if (t < 6 + length) return;
				end();
				clearCaption(mc);
				go(Step.SURVIVAL);
			}
			case SURVIVAL -> survival(mc, door, e);
			case ARC -> {
				// A quick arc round the agent that is now waiting at the whiteboard, name tag and status in frame.
				var a = waiting;
				if (t == 1) {
					cinematic(mc, false, 60);
					command(mc, "time set 11700");
				}
				boolean there = a.status == T3State.Status.NEEDS_YOU && a.zone == OfficeBrain.Zone.NEEDS_YOU && a.settled();
				if (stand == null) {
					camera(mc, d(a.x + 4, a.y() + 2.2, a.z - 3), d(a.x, a.y() + 1.3, a.z), t == 1);
					if (!there || t < 20) {
						if (t > 20 * 30) finish(mc, "FAIL the agent that needs you never reached the whiteboard (status " + a.status + ", zone " + a.zone + ")");
						return;
					}
					stand = standSpot(mc, a, Math.atan2(-2.0, -1.4));
					if (stand == null) { finish(mc, "FAIL no clear spot within reach of the waiting agent at " + a.x + ", " + a.z + " to click it from"); return; }
					standAngle = Math.atan2(stand[1] - a.z, stand[0] - a.x);
					arcSign = arcBlocked(a, 1) <= arcBlocked(a, -1) ? 1 : -1;
					T3CraftClient.LOGGER.info("DEMO standing at {}, {} for the agent at {}, {}", stand[0], stand[1], a.x, a.z);
					t = 100;
				}
				// The agent turns to whoever stands near it: park the unseen player where the next shot looks from.
				mc.player.setPos(d(stand[0], a.y(), stand[1]));
				int length = 32;
				double k = (t - 106) / (double) length, angle = standAngle + arcSign * Math.toRadians(52 - 44 * k);
				camera(mc, d(a.x + Math.cos(angle) * 3.5, a.y() + 1.85, a.z + Math.sin(angle) * 3.5), d(a.x, a.y() + 1.3, a.z), t == 101);
				if (t == 104) mc.gui.toastManager().clear();
				if (t == 106) begin("arc");
				if (t < 106 + length) return;
				end();
				go(Step.CARD);
			}
			case CARD -> card(mc, screen);
			case FOLLOW -> follow(mc);
			case TALK_WAIT -> {
				if (t == 1) {
					cinematic(mc, false, 45);
					clearCaption(mc);
					camera(mc, d(midX, 2.6, midZ - 2), d(midX - 6, 1.2, midZ - 6), true);
					command(mc, "time set 11300");
					fixture("delegate?parent=office-0&driver=codex&title=" + System.getProperty("t3craft.demoHelperTitle", "Fix the merge conflicts").replace(" ", "%20"));
				}
				var live = mod.residents().talks().director().conversations().stream().filter(c -> c.phase == AgentTalk.Phase.APPROACH).findFirst().orElse(null);
				if (live == null) {
					if (t > 20 * 60) finish(mc, "FAIL no parent and helper started talking within a minute of the delegate call");
					return;
				}
				conversation = live;
				frameTalk();
				go(Step.TALK);
			}
			case TALK -> {
				// A slow orbit and push-in on the pair, three-quarter on, while the parent hands the work over.
				var c = conversation;
				// The swing is timed from the parent's line, so the part the cut keeps always passes the same framing.
				int since = firstAt == 0 ? -12 : t - firstAt - 12;
				double angle = talkAngle + talkSide * Math.toRadians(8 + 0.26 * since), radius = 3.9 - 0.006 * since;
				// Aimed above their heads: the speech bubble over the speaker has to stay inside the frame.
				camera(mc, d(talkMid[0] + Math.cos(angle) * radius, talkFloor + 2.5, talkMid[1] + Math.sin(angle) * radius), d(talkMid[0], talkFloor + 2.15, talkMid[1]), t == 1);
				if (t == 6) begin("talk");
				if (c.phase != lastPhase) {
					lastPhase = c.phase;
					mark(c.phase.name().toLowerCase(Locale.ROOT));
					if (c.phase == AgentTalk.Phase.FIRST) firstAt = t;
					if (c.phase == AgentTalk.Phase.SECOND) secondAt = t;
				}
				boolean over = c.phase == AgentTalk.Phase.DONE || !mod.residents().talks().director().conversations().contains(c);
				if (over && secondAt == 0) { finish(mc, "FAIL the conversation ended before the helper answered"); return; }
				if (t > 20 * 70) { finish(mc, "FAIL the conversation never reached the helper's answer"); return; }
				if (!(over || (secondAt > 0 && t - secondAt > 34))) return;
				end();
				go(Step.TYPE);
			}
			case TYPE -> type(mc, screen);
			case DUSK -> {
				// Pulling back from the glass front while the afternoon runs into dusk and the lamps come on.
				int length = 70;
				if (t == 1) {
					cinematic(mc, false, 60);
					command(mc, "time set 11850");
				}
				camera(mc, duskEye((t - 14) / (double) length * 0.6, midX, e), d(midX, 5, midZ), t == 1);
				if (t == 13) mc.gui.toastManager().clear();
				if (t == 14) begin("dusk");
				if (t >= 14) command(mc, "time add 18");
				if (t == 18) caption(mc, 60, 26f, 8, false, sub("They keep working."), sub("You keep playing."));
				// The thread the player told to ship it finishes: the Done toast, wherever the player is.
				if (t == 14 + 22) fixture("finish?id=" + typedTo);
				if (segment != null && !doneSeen && t > 14 + 22 && toast(mc) && status(typedTo) == T3State.Status.DONE) { mark("done"); doneSeen = true; }
				if (t < 14 + length) return;
				end();
				if (!doneSeen) T3CraftClient.LOGGER.warn("DEMO no Done toast showed in the dusk shot");
				clearCaption(mc);
				go(Step.END_WAIT);
			}
			case END_WAIT -> {
				if (t == 1) { hud(mc, false); command(mc, "time set 13100"); }
				camera(mc, duskEye(0.6, midX, e), d(midX, 5, midZ), t == 1);
				// Lamps follow the night on the office's own timer; give it time to finish switching every floor.
				if (t < 160) return;
				command(mc, "time set 13100");
				go(Step.ENDCARD);
			}
			case ENDCARD -> {
				// The same pull-back carries on over the lit office and comes to rest under the end card: the one ease in the video.
				int length = 76;
				double s = Mth.clamp(t - 6, 0, length), k = 0.6 + 0.6 / 70 * (s - s * s / (2.0 * length));
				camera(mc, duskEye(k, midX, e), d(midX, 5, midZ), t == 1);
				if (t == 6) begin("end");
				if (t == 8) { caption(mc, 4000, 26f, 6, true, big("T3 Craft"), sub("Works with T3 Code. Free, open source."), small("github.com/alexandros-lekkas/t3craft")); mark("caption"); }
				if (t < 6 + length) return;
				end();
				go(Step.FINISH);
			}
			case FINISH -> {
				if (segments.stream().allMatch(s -> s.done)) finish(mc, "PASS " + segments.stream().map(s -> s.name + "=" + s.requested).toList());
				else if (t > 20 * 120) finish(mc, "FAIL encoders did not finish");
			}
			default -> {}
		}
	}

	/** The camera line for the opening: in front of the fullest row of working agents, above their screens. */
	private boolean frameHook(java.util.Collection<OfficeBrain.Agent> agents) {
		java.util.Map<Long, List<OfficeBrain.Agent>> rows = new java.util.TreeMap<>();
		for (var a : agents) if (a.floor == 0 && a.zone == OfficeBrain.Zone.WORK && a.status == T3State.Status.WORKING && a.settled()) {
			double rad = Math.toRadians(a.yaw);
			// Agents in one row share a heading and a position along it.
			long key = Math.round(a.yaw) * 1000L + Math.round((a.x * -Math.sin(rad) + a.z * Math.cos(rad)) * 2);
			rows.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
		}
		List<OfficeBrain.Agent> row = rows.values().stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
		if (row.size() < 2) return false;
		double rad = Math.toRadians(row.getFirst().yaw), fx = -Math.sin(rad), fz = Math.cos(rad), rx = fz, rz = -fx;
		row = row.stream().sorted(java.util.Comparator.comparingDouble(a -> a.x * rx + a.z * rz)).toList();
		var first = row.getFirst();
		// Design-space offsets become world-space ones by turning two points with the office.
		Vec3 start = d(first.x - rx * 0.9 + fx * 2.35, 2.45, first.z - rz * 0.9 + fz * 2.35);
		hookAlong = d(first.x + rx, 0, first.z + rz).subtract(d(first.x, 0, first.z));
		hookFrom = start;
		hookRow = d(first.x - rx * 0.9, 1.15, first.z - rz * 0.9);
		T3CraftClient.LOGGER.info("DEMO opening on a row of {} agents from {}", row.size(), first.threadId);
		return true;
	}

	private Vec3 duskEye(double k, double midX, int[] e) {
		return mix(d(midX - 9, 4.2, e[3] + 13), d(midX + 5, 9.5, e[3] + 30), k);
	}

	/**
	 * The player playing survival outside the office: chopping a tree with hearts, hunger and a hotbar on screen. The
	 * staged thread asks for its approval, the Needs-you toast slides in, the player turns and runs for the door.
	 */
	private void survival(Minecraft mc, int[] door, int[] e) {
		int begin = 34;
		if (t == 1) {
			stage = surveyStage(mc, door, e);
			if (stage == null) { finish(mc, "FAIL no level ground outside the office door for the survival beat"); return; }
			BlockPos tree = stage.tree();
			int x = tree.getX(), y = tree.getY(), z = tree.getZ();
			for (String command : List.of("time set 11400",
				"fill " + (x - 2) + " " + (y + 3) + " " + (z - 2) + " " + (x + 2) + " " + (y + 4) + " " + (z + 2) + " oak_leaves[persistent=true] replace air",
				"fill " + (x - 1) + " " + (y + 5) + " " + (z - 1) + " " + (x + 1) + " " + (y + 6) + " " + (z + 1) + " oak_leaves[persistent=true] replace air",
				"setblock " + x + " " + (y - 1) + " " + z + " dirt", "fill " + x + " " + y + " " + z + " " + x + " " + (y + 4) + " " + z + " oak_log")) command(mc, command);
			Vec3 eyes = stage.feet().add(0, 1.62, 0);
			aimYaw = yawTo(eyes, Vec3.atCenterOf(tree));
			firstPerson(mc, stage.feet(), aimYaw, 5, 5);
			mc.player.getInventory().setSelectedSlot(0);
			pingAt = turnAt = logAt = 0;
		}
		Vec3 eyes = stage.feet().add(0, 1.62, 0);
		BlockPos upper = stage.tree().above();
		if (turnAt == 0) {
			// Chop the log at eye level, then the one below it.
			boolean upperGone = mc.level.getBlockState(upper).isAir();
			if (upperGone && logAt == 0 && t >= begin) { logAt = t; mark("log"); }
			float want = pitchTo(eyes, new Vec3(upper.getX() + 0.5, upper.getY() + (upperGone ? -0.45 : 0.45), upper.getZ() + 0.5));
			aimPitch = t < begin ? want : aimPitch + Mth.clamp(want - aimPitch, -3.2f, 3.2f);
			look(mc, aimYaw + swayYaw(), aimPitch + swayPitch());
		}
		if (t == begin - 2) {
			boolean ready = mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SURVIVAL && mc.player.position().distanceTo(stage.feet()) < 1.2
				&& mc.level.getBlockState(upper).is(net.minecraft.tags.BlockTags.LOGS);
			if (!ready) { finish(mc, "FAIL the survival beat is not set: mode " + mc.gameMode.getPlayerMode() + ", player at " + mc.player.position() + ", wanted " + stage.feet()); return; }
			// An open screen keeps vanilla's own key handling (which would reset the mining every tick) out of the way.
			caption(mc, 38, 62f, 6, false, sub("You keep playing."));
			mc.gui.toastManager().clear();
		}
		if (t == begin) begin("survival");
		if (t >= begin && turnAt == 0 && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
			&& mc.level.getBlockState(hit.getBlockPos()).is(net.minecraft.tags.BlockTags.LOGS)) {
			if (mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) swing(mc);
		}
		if (t == begin + 12) fixture("ask?id=" + staged);
		if (pingAt == 0 && t > begin + 12) {
			if (status(staged) == T3State.Status.NEEDS_YOU && toast(mc)) { pingAt = t; mark("ping"); }
			else if (t > begin + 12 + 100) { finish(mc, "FAIL the Needs-you toast for " + staged + " never showed (status " + status(staged) + ")"); return; }
		}
		if (pingAt > 0 && turnAt == 0 && t >= pingAt + 18) {
			if (logAt == 0) { finish(mc, "FAIL the log did not break; the player is not chopping"); return; }
			turnAt = t;
			mc.gameMode.stopDestroyBlock();
			walkFrom = mc.player.position();
			caption(mc, 400, 62f, 6, false, sub("One needs you? Walk over."));
			mark("turn");
		}
		if (turnAt == 0) return;
		// Turn to the office and run for the door.
		Vec3 target = d(door[0] + 0.5, 0, door[1] + 0.5);
		float to = yawTo(mc.player.position(), target), from = aimYaw;
		double k = ease((t - turnAt) / 16.0);
		look(mc, from + Mth.wrapDegrees(to - from) * (float) k + swayYaw() * 0.4f, (float) Mth.lerp(k, aimPitch, 2) + swayPitch() * 0.4f);
		if (t == turnAt + 9) {
			mc.player.input = new net.minecraft.client.player.ClientInput() {
				@Override public void tick() {
					keyPresses = new net.minecraft.world.entity.player.Input(true, false, false, false, false, false, true);
					moveVector = new net.minecraft.world.phys.Vec2(0, 1);
				}
			};
		}
		if (t < turnAt + 9 + 30) return;
		double walked = mc.player.position().distanceTo(walkFrom);
		end();
		mc.player.input = new net.minecraft.client.player.KeyboardInput(mc.options);
		clearCaption(mc);
		if (walked < 3) { finish(mc, "FAIL the player did not run to the office (moved " + walked + " blocks)"); return; }
		go(Step.ARC);
	}

	/** Level ground a dozen blocks out from the door, with room for a tree two blocks further on. */
	private Stage surveyStage(Minecraft mc, int[] door, int[] e) {
		var level = mc.level;
		for (int back : new int[] {13, 15, 11, 17}) for (int dx : new int[] {0, -3, 3, -6, 6, -9, 9, -12, 12}) {
			double x = door[0] + 0.5 + dx, z = e[2] - back + 0.5;
			BlockPos feet = BlockPos.containing(d(x, 0, z)), mid = BlockPos.containing(d(x, 0, z - 1)), tree = BlockPos.containing(d(x, 0, z - 2));
			int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ());
			if (Math.abs(ground - frame.origin().getY()) > 3) continue;
			boolean flat = true;
			for (BlockPos p : new BlockPos[] {mid, tree}) flat &= level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) == ground;
			BlockPos below = new BlockPos(feet.getX(), ground - 1, feet.getZ());
			var state = level.getBlockState(below);
			if (!flat || !state.getFluidState().isEmpty() || state.is(net.minecraft.tags.BlockTags.LEAVES) || state.is(net.minecraft.tags.BlockTags.LOGS)
				|| state.getCollisionShape(level, below).isEmpty()) continue;
			T3CraftClient.LOGGER.info("DEMO survival beat at {} with the tree at {}", feet.atY(ground).toShortString(), tree.atY(ground).toShortString());
			return new Stage(new Vec3(feet.getX() + 0.5, ground, feet.getZ() + 0.5), tree.atY(ground));
		}
		return null;
	}

	/** First person: click the waiting agent, read its request on the card, approve it; the agent heads back to its desk. */
	private void card(Minecraft mc, Screen screen) {
		var a = waiting;
		int begin = 26;
		Vec3 eyes = d(stand[0], a.y() + 1.62, stand[1]);
		if (t == 1) {
			aim(a, eyes);
			firstPerson(mc, eyes.subtract(0, 1.62, 0), aimYaw - 14, aimPitch, 6);
			mc.player.getInventory().setSelectedSlot(0);
			approvedAt = flipAt = closedAt = 0;
		}
		// Turn onto the agent, then, once its card is open, a little further so it stands clear of the card.
		double onto = ease((t - begin) / 10.0), past = ease((t - (begin + 20)) / 12.0);
		if (closedAt == 0) look(mc, aimYaw - 14 + 14 * (float) onto + 17 * (float) past + swayYaw(), aimPitch + swayPitch());
		if (t == begin - 1) mc.gui.toastManager().clear();
		if (t == begin) begin("card");
		if (t == begin + 12) {
			boolean aimed = mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && a.threadId.equals(mod.residents().threadsByEntity().get(hit.getEntity().getId()));
			if (!aimed || mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL)
				{ finish(mc, "FAIL the crosshair is not on the waiting agent (" + mc.hitResult + ", mode " + mc.gameMode.getPlayerMode() + ")"); return; }
			net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
			swing(mc);
			mark("click");
		}
		if (t == begin + 20) {
			if (!(screen instanceof T3AgentCardScreen)) { finish(mc, "FAIL clicking the waiting agent did not open its card (screen " + screen + ")"); return; }
			mark("card");
		}
		if (t == begin + 34) {
			if (!(screen instanceof T3AgentCardScreen c) || !c.approvalActiveForTest()) { finish(mc, "FAIL the card shows no approval to answer"); return; }
			screen.setFocused(button(screen, "Approve (Y)"));
		}
		if (t == begin + 46) { press(screen, "Approve (Y)"); approvedAt = t; mark("approved"); }
		if (approvedAt > 0 && closedAt == 0) {
			boolean through = screen instanceof T3AgentCardScreen c && !c.approvalVisibleForTest() && status(staged) == T3State.Status.WORKING;
			if (through && flipAt == 0) { flipAt = t; mark("working"); }
			if (!through && t > approvedAt + 80) { finish(mc, "FAIL the approval did not go through (status " + status(staged) + ", screen " + screen + ")"); return; }
			// Long enough to see the status and the message change, then back to the world.
			if (flipAt > 0 && t >= flipAt + 10) { mc.gui.setScreen(null); closedAt = t; mark("closed"); }
		}
		// Watch it walk off.
		if (closedAt > 0) {
			Vec3 head = d(a.x, a.y() + 1.4, a.z);
			float y = mc.player.getYRot(), wantYaw = yawTo(eyes, head);
			look(mc, y + Mth.clamp(Mth.wrapDegrees(wantYaw - y) * 0.3f, -7, 7), mc.player.getXRot() + (pitchTo(eyes, head) - mc.player.getXRot()) * 0.3f);
		}
		if (closedAt == 0 || t < closedAt + 18) return;
		end();
		go(Step.FOLLOW);
	}

	/** Points {@link #aimYaw}/{@link #aimPitch} at the agent's head, a touch to its side so it stands left of the crosshair. */
	private void aim(OfficeBrain.Agent a, Vec3 eyes) {
		Vec3 head = d(a.x, a.y() + 1.4, a.z), to = head.subtract(eyes);
		double flat = Math.max(1e-6, Math.hypot(to.x, to.z));
		Vec3 at = head.add(-to.z / flat * 0.2, 0, to.x / flat * 0.2);
		aimYaw = yawTo(eyes, at);
		aimPitch = pitchTo(eyes, at);
	}

	/** Leads the approved agent back to its desk and settles in front of it as it sits down and starts typing. */
	private void follow(Minecraft mc) {
		var a = waiting;
		if (t == 1) { cinematic(mc, false, 60); command(mc, "time set 11800"); mc.gui.toastManager().clear(); followEye = null; followDir = null; seatedAt = captionAt = followAt = 0; }
		boolean heading = a.zone == OfficeBrain.Zone.WORK && a.goal != null && a.goal.seat();
		if (!heading) {
			if (t > 60) finish(mc, "FAIL the approved agent is not heading back to a desk (zone " + a.zone + ")");
			return;
		}
		double gx = a.goal.x() + 0.5, gz = a.goal.z() + 0.5, tx = gx - a.x, tz = gz - a.z, dist = Math.hypot(tx, tz);
		if (dist > 0.4 || followDir == null) followDir = new Vec3(tx / Math.max(dist, 1e-6), 0, tz / Math.max(dist, 1e-6));
		double rad = Math.toRadians(a.goal.yaw()), fx = -Math.sin(rad), fz = Math.cos(rad);
		Vec3 lead = new Vec3(a.x + followDir.x * 3.2 - followDir.z * 1.5, a.y() + 1.95, a.z + followDir.z * 3.2 + followDir.x * 1.5);
		Vec3 seat = new Vec3(gx + fx * 2.35 + fz * 1.1, 2.35 + a.floor * T3Office.FLOOR_HEIGHT, gz + fz * 2.35 - fx * 1.1);
		Vec3 want = mix(lead, seat, ease((4.5 - dist) / 3.0));
		boolean first = followEye == null;
		if (first) followAt = t;
		followEye = first ? want : mix(followEye, want, 0.16);
		camera(mc, d(followEye.x, followEye.y, followEye.z), d(a.x, a.y() + 1.3, a.z), first);
		if (t == Math.max(6, followAt + 3)) begin("follow");
		if (captionAt == 0 && dist < 3.6) { captionAt = t; caption(mc, 56, 26f, 6, false, sub("Answer it without leaving the game.")); mark("near"); }
		if (seatedAt == 0 && a.settled() && dist < 0.8) { seatedAt = t; mark("seated"); }
		if (t > 20 * 25) { finish(mc, "FAIL the approved agent never sat back down at its desk"); return; }
		if (seatedAt == 0 || t < seatedAt + 30) return;
		end();
		clearCaption(mc);
		go(Step.TALK_WAIT);
	}

	/** Three-quarter on the pair from whichever side keeps the camera inside the room. */
	private void frameTalk() {
		var c = conversation;
		double[] a = c.route.parentMeet(), b = c.route.helperMeet();
		double mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2, axis = Math.atan2(b[1] - a[1], b[0] - a[0]);
		boolean[][] blocked = T3Office.blocked();
		int best = Integer.MAX_VALUE;
		// Side-on is a quarter turn from the line between them; the swing runs from there toward the parent's side, onto the helper's face.
		for (int side : new int[] {1, -1}) {
			int inTheWay = 0;
			for (int i = 0; i <= 8; i++) {
				double angle = axis + side * (Math.PI / 2 + Math.toRadians(4 * i));
				int x = (int) Math.floor(mx + Math.cos(angle) * 4.3), z = (int) Math.floor(mz + Math.sin(angle) * 4.3);
				if (x < 1 || z < 1 || x >= T3Office.WIDTH - 1 || z >= T3Office.LENGTH - 1) inTheWay += 20;
				else if (blocked[x][z]) inTheWay++;
			}
			if (inTheWay < best) { best = inTheWay; talkSide = side; }
		}
		talkAngle = axis + talkSide * Math.PI / 2;
		talkMid = new double[] {mx, mz};
		talkFloor = c.parent.floor * T3Office.FLOOR_HEIGHT;
	}

	/** First person: click a finished agent, type a short message on its card key by key, send it; the agent goes back to work. */
	private void type(Minecraft mc, Screen screen) {
		var a = typist;
		int begin = 26;
		if (t == 1) {
			if (a.status != T3State.Status.DONE || !a.settled()) { finish(mc, "FAIL the agent to type to is not resting (status " + a.status + ")"); return; }
			double rad = Math.toRadians(a.yaw);
			stand = standSpot(mc, a, Math.atan2(Math.cos(rad), -Math.sin(rad)));
			if (stand == null) { finish(mc, "FAIL no clear spot within reach of the finished agent at " + a.x + ", " + a.z + " to click it from"); return; }
			Vec3 eyes = d(stand[0], a.floor * T3Office.FLOOR_HEIGHT + 1.62, stand[1]);
			aim(a, eyes);
			firstPerson(mc, eyes.subtract(0, 1.62, 0), aimYaw, aimPitch, 6);
			command(mc, "time set 11900");
			typedAt = sentAt = workingAt = nextKey = 0;
		}
		double past = ease((t - (begin + 12)) / 12.0);
		if (sentAt == 0) look(mc, aimYaw + 17 * (float) past + swayYaw(), aimPitch + swayPitch());
		else look(mc, aimYaw + 17 - 12 * (float) ease((t - sentAt) / 14.0) + swayYaw(), aimPitch + swayPitch());
		if (t == begin - 1) mc.gui.toastManager().clear();
		if (t == begin) begin("type");
		if (t == begin + 4) {
			boolean aimed = mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && a.threadId.equals(mod.residents().threadsByEntity().get(hit.getEntity().getId()));
			if (!aimed) { finish(mc, "FAIL the crosshair is not on the finished agent (" + mc.hitResult + ")"); return; }
			net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
			swing(mc);
			mark("click");
		}
		if (t == begin + 12) {
			if (!(screen instanceof T3AgentCardScreen)) { finish(mc, "FAIL clicking the finished agent did not open its card (screen " + screen + ")"); return; }
			mark("card");
		}
		// A key every three ticks or so, with a longer beat after the space: about seven characters a second.
		if (screen instanceof T3AgentCardScreen card && typedAt == 0 && t >= begin + 20) {
			int index = card.inputValueForTest().length();
			if (index < TYPED.length() && t >= nextKey) {
				screen.charTyped(new net.minecraft.client.input.CharacterEvent(TYPED.charAt(index)));
				nextKey = t + (TYPED.charAt(index) == ' ' ? 5 : 2 + index % 2);
			}
			if (card.inputValueForTest().equals(TYPED)) { typedAt = t; mark("typed"); }
			else if (t > begin + 100) { finish(mc, "FAIL the message was not typed into the card (it reads \"" + card.inputValueForTest() + "\")"); return; }
		}
		if (typedAt > 0 && sentAt == 0 && t >= typedAt + 8) {
			screen.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
			if (mc.gui.screen() instanceof T3AgentCardScreen) { finish(mc, "FAIL Enter did not send the message from the card"); return; }
			sentAt = t;
			mark("sent");
		}
		if (sentAt > 0 && workingAt == 0) {
			if (status(typedTo) == T3State.Status.WORKING) { workingAt = t; mark("working"); }
			else if (t > sentAt + 60) { finish(mc, "FAIL the agent did not start working after the message (status " + status(typedTo) + ")"); return; }
		}
		if (workingAt == 0 || t < Math.max(sentAt + 24, workingAt + 12)) return;
		end();
		go(Step.DUSK);
	}

	/**
	 * Where the player stands to click an agent: about two and a half blocks away, as near {@code preferred} (a design
	 * angle from the agent) as the room allows. A spot counts only if the player fits there and nothing in the world
	 * stands between its eyes and the agent's head, so it follows the furniture rather than a fixed layout.
	 */
	private double[] standSpot(Minecraft mc, OfficeBrain.Agent a, double preferred) {
		boolean[][] blocked = T3Office.blocked();
		double floor = a.floor * T3Office.FLOOR_HEIGHT;
		for (int offset : new int[] {0, 12, -12, 24, -24, 36, -36, 48, -48, 60, -60, 75, -75, 90, -90, 110, -110, 130, -130, 150, -150, 180}) {
			for (double reach : new double[] {2.6, 2.3, 2.85}) {
				double angle = preferred + Math.toRadians(offset), cx = a.x + Math.cos(angle) * reach, cz = a.z + Math.sin(angle) * reach;
				boolean clear = true;
				for (double dx = -0.35; dx <= 0.35 && clear; dx += 0.35) for (double dz = -0.35; dz <= 0.35 && clear; dz += 0.35) {
					clear = !blockedAt(blocked, cx + dx, cz + dz);
					BlockPos feet = BlockPos.containing(d(cx + dx, floor + 0.1, cz + dz));
					for (BlockPos p : new BlockPos[] {feet, feet.above()}) clear &= mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
					clear &= !mc.level.getBlockState(feet.below()).getCollisionShape(mc.level, feet.below()).isEmpty();
				}
				if (!clear) continue;
				Vec3 eyes = d(cx, floor + 1.62, cz), head = d(a.x, a.y() + 1.4, a.z);
				for (Vec3 to : new Vec3[] {head, head.add(0, -0.2, 0)}) {
					clear &= mc.level.clip(new net.minecraft.world.level.ClipContext(eyes, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
						net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
				}
				if (clear) return new double[] {cx, cz};
			}
		}
		return null;
	}

	/** How much furniture and wall the arc round the waiting agent would pass through on one side. */
	private int arcBlocked(OfficeBrain.Agent a, int sign) {
		boolean[][] blocked = T3Office.blocked();
		int count = 0;
		for (int i = 0; i <= 8; i++) {
			double angle = standAngle + sign * Math.toRadians(52 - 44 * i / 8.0);
			if (blockedAt(blocked, a.x + Math.cos(angle) * 3.5, a.z + Math.sin(angle) * 3.5)) count++;
		}
		return count;
	}

	private static boolean blockedAt(boolean[][] blocked, double x, double z) {
		int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
		return xi < 0 || zi < 0 || xi >= T3Office.WIDTH || zi >= T3Office.LENGTH || blocked[xi][zi];
	}

	// ---- Helpers ----

	private static void hud(Minecraft mc, boolean shown) {
		if (mc.gui.hud.isHidden() == shown) mc.gui.hud.toggle();
	}

	private static void gui(Minecraft mc, int scale) {
		if (mc.options.guiScale().get() == scale) return;
		mc.options.guiScale().set(scale);
		mc.resizeGui();
	}

	private T3State.Status status(String threadId) {
		return mod.state().snapshot().threads().stream().filter(r -> r.id().equals(threadId)).map(T3State.ThreadRow::status).findFirst().orElse(null);
	}

	private static boolean toast(Minecraft mc) {
		return mc.gui.toastManager().getToast(net.minecraft.client.gui.components.toasts.SystemToast.class, T3CraftClient.TOAST) != null;
	}

	private static void command(Minecraft mc, String command) {
		var server = mc.getSingleplayerServer();
		if (server != null) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
	}

	private static Button button(Screen screen, String label) {
		return screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(label))
			.map(c -> (Button) c).findFirst().orElseThrow(() -> new IllegalStateException("no button " + label + " on " + screen.getClass().getSimpleName()));
	}

	private static void press(Screen screen, String label) {
		Button button = button(screen, label);
		screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0,
			new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
	}

	/** A control call to the office fixture ({@code scripts/office/fixture.py}); never a real T3. */
	private void fixture(String action) {
		int port = Integer.getInteger("t3craft.fixturePort", 25720);
		Thread.ofVirtual().start(() -> {
			try {
				var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/_qa/" + action)).timeout(java.time.Duration.ofSeconds(3)).GET().build();
				var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
				T3CraftClient.LOGGER.info("DEMO fixture {} -> {}", action, response.statusCode());
			} catch (Exception e) {
				T3CraftClient.LOGGER.warn("DEMO fixture control failed: {}", e.toString());
			}
		});
	}

	private void finish(Minecraft mc, String result) {
		end();
		step = Step.DONE;
		T3CraftClient.LOGGER.info("DEMO RESULT {}", result);
		mc.execute(mc::stop);
	}

	// ---- Captions ----

	private static Caption.Line big(String text) { return new Caption.Line(text, 3.2f, 0xFFFFFF); }
	private static Caption.Line sub(String text) { return new Caption.Line(text, 2.2f, 0xFFFFFF); }
	private static Caption.Line small(String text) { return new Caption.Line(text, 1.5f, 0xC8D0DA); }

	/** Shows a caption (reusing the caption screen if it is up). {@code anchor} below 1 is the top edge as a share of the height, otherwise the gap under the box. */
	private static void caption(Minecraft mc, int life, float anchor, int fadeIn, boolean hold, Caption.Line... lines) {
		Caption caption = mc.gui.screen() instanceof Caption open ? open : new Caption();
		caption.show(List.of(lines), life, anchor, fadeIn, hold);
		if (mc.gui.screen() != caption) mc.gui.setScreen(caption);
	}

	private static void clearCaption(Minecraft mc) {
		if (mc.gui.screen() instanceof Caption) mc.gui.setScreen(null);
	}

	/**
	 * A caption over the world, in the game's own font, sized to the width of the frame so it reads on a phone. Drawn
	 * as a screen, so it shows over the HUD but not together with the agent card or the laptop.
	 */
	private static final class Caption extends Screen {
		record Line(String text, float scale, int rgb) {}

		private List<Line> lines = List.of();
		private int life, age, fadeIn = 8;
		private float anchor;
		private boolean hold;

		Caption() { super(Component.literal("T3 Craft")); }

		void show(List<Line> lines, int life, float anchor, int fadeIn, boolean hold) {
			this.lines = lines; this.life = life; this.anchor = anchor; this.fadeIn = fadeIn; this.hold = hold;
			age = 0;
		}

		@Override public boolean isPauseScreen() { return false; }
		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void tick() { age++; }
		@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			if (lines.isEmpty()) return;
			float now = age + Mth.clamp(a, 0, 1);
			float alpha = Mth.clamp(Math.min(now / fadeIn, hold ? 1 : (life - now) / 8f), 0, 1);
			if (alpha < 0.03f) return;
			float widest = 1;
			for (Line line : lines) widest = Math.max(widest, font.width(line.text()) * line.scale());
			float fit = Math.min(1, (width * 0.9f - 20) / widest), w = widest * fit + 20, h = 10;
			for (Line line : lines) h += (font.lineHeight + 2) * line.scale() * fit;
			float x0 = (width - w) / 2f, y0 = anchor < 1 ? height * anchor : height - h - anchor;
			graphics.fill((int) x0, (int) y0, (int) (x0 + w), (int) (y0 + h), (int) (alpha * 0x96) << 24);
			var pose = graphics.pose();
			float y = y0 + 6;
			for (Line line : lines) {
				float scale = line.scale() * fit;
				pose.pushMatrix();
				pose.translate(width / 2f, y);
				pose.scale(scale, scale);
				graphics.text(font, line.text(), -font.width(line.text()) / 2, 0, ((int) (alpha * 255) << 24) | line.rgb(), true);
				pose.popMatrix();
				y += (font.lineHeight + 2) * scale;
			}
		}
	}
}
