package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
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
 * scene against the office fixture and records each shot from the game's own framebuffer into
 * {@code <output dir>/seg-<name>.mp4} through an ffmpeg child process. Nothing depends on the window, the mouse or
 * the keyboard:
 * <ul>
 * <li>the camera is a client-only marker entity moved every tick along eased paths, so vanilla's own
 * interpolation makes the motion smooth at any frame rate;</li>
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

	private enum Step { TITLE, FORM, ARRIVE, SETTLE, EXTERIOR, DESKS, NEEDS, TALK_WAIT, TALK, LAPTOP, NIGHT_WAIT, NIGHT, FINISH, DONE }

	private record Frame(Segment segment, NativeImage image) {}
	private record Marker(net.minecraft.core.BlockPos origin, net.minecraft.world.level.block.Rotation rotation) {}

	private final T3CraftClient mod;
	private final Path out;
	private final String place = System.getProperty("t3craft.demoPlace", "Lakeside");
	private final String ffmpeg = System.getProperty("t3craft.demoFfmpeg", "ffmpeg");
	private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(6);
	private final List<Segment> segments = new ArrayList<>();
	private Step step = Step.TITLE;
	private int t, deadline = 20 * 60 * 12;
	private OfficeFrame frame;
	private java.util.concurrent.CompletableFuture<Marker> reading;

	// Frame clock and capture (render thread).
	private boolean fixed;
	private double virtualMs = -1;
	private volatile Segment segment;

	// Camera.
	private Entity cam;
	private Vec3 eye = Vec3.ZERO;
	private float yaw, pitch;
	private boolean follow = true;

	// Scene state.
	private OfficeBrain.Agent waiting;
	private Vec3 standEye, agentHead;
	private AgentTalk.Conversation conversation;
	private AgentTalk.Phase lastPhase;
	private Vec3 talkEye, talkTarget;
	private int leaveAt;

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

	/** A point on the curve from {@code a} to {@code b} bent toward {@code via}. */
	private static Vec3 bend(Vec3 a, Vec3 via, Vec3 b, double k) { return mix(mix(a, via, k), mix(via, b, k), k); }

	/** Moves the camera to look from {@code from} at {@code at}; a cut jumps, otherwise the frame interpolates from the last tick. */
	private void camera(Minecraft mc, Vec3 from, Vec3 at, boolean cut) {
		if (cam == null || cam.isRemoved() || cam.level() != mc.level) {
			cam = net.minecraft.world.entity.EntityTypes.MARKER.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			cam.setId(-7900001);
			mc.level.addEntity(cam);
			cut = true;
		}
		double dx = at.x - from.x, dy = at.y - from.y, dz = at.z - from.z;
		float newYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90, newPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		newYaw = cut ? Mth.wrapDegrees(newYaw) : yaw + Mth.wrapDegrees(newYaw - yaw);
		Vec3 old = cut ? from : eye;
		float oldYaw = cut ? newYaw : yaw, oldPitch = cut ? newPitch : pitch;
		cam.setPos(from.x, from.y, from.z);
		cam.setYRot(newYaw);
		cam.setXRot(newPitch);
		cam.xo = cam.xOld = old.x; cam.yo = cam.yOld = old.y; cam.zo = cam.zOld = old.z;
		cam.yRotO = oldYaw; cam.xRotO = oldPitch;
		eye = from; yaw = newYaw; pitch = newPitch;
		if (mc.getCameraEntity() != cam && follow) mc.setCameraEntity(cam);
	}

	/** The invisible player rides with the camera so the chunks around it stay loaded. */
	private void followCamera(Minecraft mc) {
		if (!follow || cam == null || mc.player == null) return;
		var player = mc.player;
		player.getAbilities().flying = true;
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
	}

	private void firstPerson(Minecraft mc, Vec3 lookAt) {
		follow = false;
		mc.setCameraEntity(mc.player);
		mc.player.getAbilities().flying = true;
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, lookAt);
	}

	private void cinematic(Minecraft mc) {
		follow = true;
		if (cam != null) mc.setCameraEntity(cam);
		hud(mc, false);
	}

	// ---- Script ----

	void tick(Minecraft mc) {
		if (step == Step.DONE) return;
		if (--deadline == 0) { finish(mc, "FAIL timed out at " + step); return; }
		try {
			t++;
			script(mc);
			if (step.ordinal() > Step.ARRIVE.ordinal() && step != Step.DONE && mc.player != null) {
				followCamera(mc);
				// The player's head may brush a wall while it rides with the camera; vanilla resets this flag at the start
				// of every tick, so setting it after the tick only keeps the "inside a block" overlay out of the frames.
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
					mc.options.guiScale().set(4);
					mc.resizeGui();
					for (String command : List.of("gamemode creative Player", "difficulty peaceful", "weather clear", "time set 1000",
						"effect give Player minecraft:invisibility infinite 0 true")) command(mc, command);
					hud(mc, false);
					fixed = true;
				}
				camera(mc, exteriorEye(0, door, e), exteriorTarget(0, door, midX, midZ), t == 1);
				var agents = mod.residents().brainForTest().agents().values();
				waiting = agents.stream().filter(a -> a.status == T3State.Status.NEEDS_YOU).findFirst().orElse(null);
				boolean seated = !agents.isEmpty() && agents.stream().allMatch(OfficeBrain.Agent::settled);
				if (t < 300 || (!(seated && waiting != null) && t < 20 * 75)) return;
				if (waiting == null) { finish(mc, "FAIL no agent is waiting on the player; is the fixture running with --demo?"); return; }
				T3CraftClient.LOGGER.info("DEMO office {} rotation {}, {} agents, all seated {}", frame.origin().toShortString(), frame.rotation(), agents.size(), seated);
				command(mc, "time set 1000");
				go(Step.EXTERIOR);
			}
			case EXTERIOR -> {
				// From above the plaza down to the door.
				int length = 130;
				double k = ease((t - 6) / (double) length);
				camera(mc, exteriorEye(k, door, e), exteriorTarget(k, door, midX, midZ), t == 1);
				if (t == 6) begin("exterior");
				if (t == 22) { mc.gui.setScreen(new Caption(List.of("Your coding agents,", "living in your Minecraft world."), 96)); mark("caption"); }
				if (t == 120) mc.gui.setScreen(null);
				if (t < 6 + length) return;
				end();
				go(Step.DESKS);
			}
			case DESKS -> {
				// Behind the bench, drifting along the row of agents at their desks.
				int length = 84;
				double k = ease((t - 6) / (double) length);
				camera(mc, mix(d(3.6, 2.5, 12.6), d(9.4, 2.1, 11.0), k), mix(d(5.5, 0.9, 5.0), d(7.5, 0.9, 5.0), k), t == 1);
				if (t == 6) begin("desks");
				if (t < 6 + length) return;
				end();
				go(Step.NEEDS);
			}
			case NEEDS -> needsYou(mc, screen);
			case TALK_WAIT -> {
				if (t == 1) {
					camera(mc, d(6.5, 2.3, 11.5), d(6.5, 0.9, 5.0), true);
					fixture("delegate?parent=office-0&title=Write%20the%20release%20notes");
				}
				var live = mod.residents().talks().director().conversations().stream().filter(c -> c.phase == AgentTalk.Phase.APPROACH).findFirst().orElse(null);
				if (live == null) {
					if (t > 20 * 60) { T3CraftClient.LOGGER.warn("DEMO talk cut: no conversation started"); go(Step.LAPTOP); }
					return;
				}
				conversation = live;
				frameTalk();
				go(Step.TALK);
			}
			case TALK -> {
				var c = conversation;
				camera(mc, talkEye, talkTarget, t == 1);
				if (t == 6) begin("talk");
				if (c.phase != lastPhase) { lastPhase = c.phase; mark(c.phase.name().toLowerCase(Locale.ROOT)); }
				if (c.phase == AgentTalk.Phase.LEAVE && leaveAt == 0) leaveAt = t;
				boolean over = c.phase == AgentTalk.Phase.DONE || !mod.residents().talks().director().conversations().contains(c);
				if (!(over || (leaveAt > 0 && t - leaveAt > 50) || t > 20 * 70)) return;
				end();
				go(Step.LAPTOP);
			}
			case LAPTOP -> {
				if (t == 1) camera(mc, d(6.5, 2.3, 11.5), d(6.5, 0.9, 5.0), true);
				if (t == 8) { firstPerson(mc, d(6.5, 0.9, 5.0)); mod.focus("office-6"); }
				if (t == 14) mod.openLaptop();
				if (t == 34) {
					if (!(screen instanceof T3Screen)) { T3CraftClient.LOGGER.warn("DEMO laptop cut: screen is {}", screen); cinematic(mc); go(Step.NIGHT_WAIT); return; }
					begin("laptop");
				}
				if (t == 58 && screen instanceof T3Screen laptop) { laptop.setComposerForTest("Add a dark mode toggle to the settings page"); mark("typed"); }
				if (t == 80 && screen instanceof T3Screen laptop) { laptop.typeAndSubmit("Add a dark mode toggle to the settings page", true); mark("sent"); }
				if (t < 116) return;
				end();
				mc.gui.setScreen(null);
				cinematic(mc);
				go(Step.NIGHT_WAIT);
			}
			case NIGHT_WAIT -> {
				if (t == 1) { hud(mc, false); command(mc, "time set 13600"); }
				camera(mc, nightEye(0, midX, e), d(midX, 5, midZ), t == 1);
				// Lamps follow the night on the office's 100-tick decoration timer.
				if (t < 240) return;
				command(mc, "time set 13600");
				go(Step.NIGHT);
			}
			case NIGHT -> {
				int length = 132;
				double k = ease((t - 6) / (double) length);
				camera(mc, nightEye(k, midX, e), d(midX, 5, midZ), t == 1);
				if (t == 6) begin("night");
				if (t == 34) { mc.gui.setScreen(new Caption(List.of("T3 Craft", "github.com/alexandros-lekkas/t3craft"), 400)); mark("caption"); }
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

	private Vec3 exteriorEye(double k, int[] door, int[] e) {
		return bend(d(door[0] + 26, 17, e[2] - 44), d(door[0] + 16, 5.5, e[2] - 24), d(door[0] + 0.5, 2.3, e[2] - 7.5), k);
	}

	private Vec3 exteriorTarget(double k, int[] door, double midX, double midZ) {
		return mix(d(midX, 6, midZ), d(door[0] + 0.5, 2.0, door[1] + 4), k);
	}

	private Vec3 nightEye(double k, double midX, int[] e) {
		return mix(d(midX - 9, 4.2, e[3] + 15), d(midX + 5, 9.5, e[3] + 30), k);
	}

	/** First person: walk up to the agent at the whiteboard, click it, approve its request on the card. */
	private void needsYou(Minecraft mc, Screen screen) {
		var a = waiting;
		if (t == 1) {
			double[] spot = standSpot(a);
			standEye = d(spot[0], a.y() + 0.3 + mc.player.getEyeHeight(), spot[1]);
			// Aim a little to the agent's side (still on it), so it stands clear of the card that opens right of the crosshair.
			Vec3 head = d(a.x, a.y() + 1.4, a.z), to = head.subtract(standEye);
			double flat = Math.max(1e-6, Math.hypot(to.x, to.z));
			agentHead = head.add(-to.z / flat * 0.2, 0, to.x / flat * 0.2);
			T3CraftClient.LOGGER.info("DEMO standing at {}, {} for the agent at {}, {}", spot[0], spot[1], a.x, a.z);
			hud(mc, false);
		}
		// A turn of the head from the room to the agent. The camera stays on the one spot known to be clear of pillars.
		if (t <= 40) {
			Vec3 to = agentHead.subtract(standEye);
			double turn = Math.toRadians(-42 * (1 - ease((t - 6) / 30.0)));
			camera(mc, standEye, standEye.add(to.x * Math.cos(turn) - to.z * Math.sin(turn), to.y, to.x * Math.sin(turn) + to.z * Math.cos(turn)), t == 1);
		}
		if (t == 6) begin("needs");
		if (t == 36) {
			Vec3 feet = standEye.subtract(0, mc.player.getEyeHeight(), 0);
			command(mc, String.format(Locale.ROOT, "tp Player %.3f %.3f %.3f %.2f %.2f", feet.x, feet.y, feet.z, Mth.wrapDegrees(yaw), pitch));
		}
		if (t == 40) {
			firstPerson(mc, agentHead);
			mc.gui.hud.getChat().clearMessages(false);
			hud(mc, true);
			mark("firstperson");
		}
		if (t > 40 && t < 50) mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, agentHead);
		if (t == 50) {
			boolean aimed = mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && a.threadId.equals(mod.residents().threadsByEntity().get(hit.getEntity().getId()));
			if (!aimed) T3CraftClient.LOGGER.warn("DEMO crosshair is not on the waiting agent: {}", mc.hitResult);
			net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
			mark("click");
		}
		if (t == 58) {
			if (!(screen instanceof T3AgentCardScreen)) { T3CraftClient.LOGGER.warn("DEMO the click did not open the card; opening it directly"); mc.gui.setScreen(new T3AgentCardScreen(mod, a.threadId)); }
			mark("card");
		}
		if (t == 100) {
			if (screen instanceof T3AgentCardScreen card && card.approvalActiveForTest()) press(screen, "Approve (Y)");
			else T3CraftClient.LOGGER.warn("DEMO no approval to press on {}", screen);
			mark("approved");
		}
		if (t == 138) { mc.gui.setScreen(null); mark("closed"); }
		if (t < 158) return;
		end();
		cinematic(mc);
		go(Step.TALK_WAIT);
	}

	/**
	 * Where the player stands to click the waiting agent: about two and a half blocks away on the side it faces (the
	 * spot the nativeui capture uses), turned toward whichever open floor is furthest from walls and furniture.
	 */
	private static double[] standSpot(OfficeBrain.Agent a) {
		boolean[][] blocked = T3Office.blocked();
		double facing = Math.atan2(-2.0, -1.4);
		double[] best = {a.x - 1.4, a.z - 2.0};
		int bestScore = -1;
		for (int offset : new int[] {0, 10, -10, 20, -20, 30, -30, 40, -40, 50, -50, 60, -60}) {
			double angle = facing + Math.toRadians(offset), cx = a.x + Math.cos(angle) * 2.6, cz = a.z + Math.sin(angle) * 2.6;
			boolean clear = true;
			for (double dx = -0.7; dx <= 0.7 && clear; dx += 0.7) for (double dz = -0.7; dz <= 0.7 && clear; dz += 0.7) clear = !blockedAt(blocked, cx + dx, cz + dz);
			for (int i = 1; i <= 8 && clear; i++) clear = !blockedAt(blocked, cx + (a.x - cx) * i / 10.0, cz + (a.z - cz) * i / 10.0);
			if (!clear) continue;
			int score = 0;
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (!blockedAt(blocked, cx + dx, cz + dz)) score++;
			if (score > bestScore) { bestScore = score; best = new double[] {cx, cz}; }
		}
		return best;
	}

	private static boolean blockedAt(boolean[][] blocked, double x, double z) {
		int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
		return xi < 0 || zi < 0 || xi >= T3Office.WIDTH || zi >= T3Office.LENGTH || blocked[xi][zi];
	}

	/** Side-on to the pair, on the side with the least furniture in the way (the framing the world self-test uses). */
	private void frameTalk() {
		var c = conversation;
		double[] a = c.route.parentMeet(), b = c.route.helperMeet();
		double mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2, ax = b[0] - a[0], az = b[1] - a[1], len = Math.max(1e-6, Math.hypot(ax, az));
		double px = -az / len, pz = ax / len;
		boolean[][] blocked = T3Office.blocked();
		double[] spot = null;
		int best = Integer.MAX_VALUE;
		// The camera floats above the furniture, so it may hang over a desk; it only has to stay inside the room.
		for (double dist : new double[] {3.2, -3.2, 3.6, -3.6, 2.8, -2.8}) {
			double cx = mx + px * dist, cz = mz + pz * dist;
			int x0 = (int) Math.floor(cx), z0 = (int) Math.floor(cz);
			if (x0 < 2 || z0 < 2 || x0 >= T3Office.WIDTH - 2 || z0 >= T3Office.LENGTH - 2) continue;
			int inTheWay = 0;
			for (int i = 1; i <= 12; i++) {
				double f = i / 12.0 * 0.8;
				int x = (int) Math.floor(cx + (mx - cx) * f), z = (int) Math.floor(cz + (mz - cz) * f);
				if (x < 0 || z < 0 || x >= T3Office.WIDTH || z >= T3Office.LENGTH || blocked[x][z]) inTheWay++;
			}
			if (inTheWay < best) { best = inTheWay; spot = new double[] {cx, cz}; }
		}
		if (spot == null) spot = new double[] {mx - ax / len * 3.5, mz - az / len * 3.5};
		double floorY = c.parent.floor * T3Office.FLOOR_HEIGHT;
		talkEye = d(spot[0], floorY + 2.35, spot[1]);
		talkTarget = d(mx, floorY + 1.85, mz);
	}

	// ---- Helpers ----

	private static void hud(Minecraft mc, boolean shown) {
		if (mc.gui.hud.isHidden() == shown) mc.gui.hud.toggle();
	}

	private static void command(Minecraft mc, String command) {
		var server = mc.getSingleplayerServer();
		if (server != null) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
	}

	private static void press(Screen screen, String label) {
		Button button = screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(label))
			.map(c -> (Button) c).findFirst().orElseThrow(() -> new IllegalStateException("no button " + label + " on " + screen.getClass().getSimpleName()));
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

	/** A caption over the world, in the game's own font: fades in, holds, fades out. Drawn as a screen so it shows with the HUD hidden. */
	private static final class Caption extends Screen {
		private final List<String> lines;
		private final int life;
		private int age;

		Caption(List<String> lines, int life) {
			super(Component.literal("T3 Craft"));
			this.lines = lines;
			this.life = life;
		}

		@Override public boolean isPauseScreen() { return false; }
		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void tick() { age++; }
		@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			float now = age + Mth.clamp(a, 0, 1);
			float alpha = Mth.clamp(Math.min(now / 12f, (life - now) / 12f), 0, 1);
			if (alpha < 0.03f) return;
			float scale = 2f;
			int widest = lines.stream().mapToInt(font::width).max().orElse(0);
			float w = widest * scale + 20, h = lines.size() * (font.lineHeight + 3) * scale + 12;
			float x0 = (width - w) / 2f, y0 = height - h - 26;
			graphics.fill((int) x0, (int) y0, (int) (x0 + w), (int) (y0 + h), (int) (alpha * 0x8C) << 24);
			int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
			var pose = graphics.pose();
			for (int i = 0; i < lines.size(); i++) {
				pose.pushMatrix();
				pose.translate(width / 2f, y0 + 8 + i * (font.lineHeight + 3) * scale);
				pose.scale(scale, scale);
				graphics.text(font, lines.get(i), -font.width(lines.get(i)) / 2, 0, i == 0 || lines.size() == 2 && !lines.get(0).equals("T3 Craft") ? color : (color & 0xFF000000) | 0xC8D0DA, true);
				pose.popMatrix();
			}
		}
	}
}
