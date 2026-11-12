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
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dev-only launch-video recorder, enabled with {@code -Dt3craft.demo=<output dir>} ({@code ./gradlew runClient -Pdemo=…},
 * normally through {@code scripts/demo/record.sh}). It creates a T3 World from the title screen, then plays a list of
 * shots against the office fixture (positions come from the office's own anchors and the agents, and a beat that
 * does not happen ends the run with {@code DEMO RESULT FAIL} and the reason) and records each shot from the game's own
 * framebuffer into {@code <output dir>/seg-<name>.mp4} through an ffmpeg child process. Nothing depends on the window,
 * the mouse or the keyboard:
 * <ul>
 * <li>the player is a real survival player: it moves through the game's own movement input (walk, sprint, jump,
 * sneak), so acceleration, view bobbing and the walk animation are the game's, and its view is turned every frame by
 * a model of a person's hand on a mouse ({@link DemoHand});</li>
 * <li>cinematic and third-person shots place the camera through {@code DemoCameraMixin}, every frame, following
 * their subject like a camera operator; in the third-person ones the player stays the camera entity, so the game
 * draws and moves it as in F5;</li>
 * <li>while it runs the game clock advances by exactly 1/60 s per rendered frame ({@code DemoClockMixin}), so the
 * video is steady however long a frame takes to capture.</li>
 * </ul>
 * {@code marks.txt} in the output directory notes the frame of each beat inside its segment for the edit, and
 * {@code look.csv} the view and position of the player in every recorded frame. {@code -Dt3craft.demoShots=a,b}
 * records only those shots, each of which stages what it needs.
 */
public final class DemoRecorder {
	private static final int FPS = 60;
	private static volatile DemoRecorder instance;

	/** The game clock for this frame; real time unless a recording is running. Called once per frame by the mixin. */
	public static long clock(long realMs) {
		DemoRecorder recorder = instance;
		return recorder == null ? realMs : recorder.frame(realMs);
	}

	/** Where the camera is for this frame, {x, y, z, yaw, pitch}, or null to leave it on its entity. Called by the camera mixin. */
	public static double[] camera(float partialTick) {
		DemoRecorder recorder = instance;
		return recorder == null ? null : recorder.pose(partialTick);
	}

	private enum Step { TITLE, FORM, ARRIVE, SETTLE, SHOTS, FINISH, DONE }
	/** Who holds the camera: an unseen operator, an operator filming the player, or the player's own eyes. */
	private enum View { CAMERA, THIRD, FIRST }

	private record Frame(Segment segment, NativeImage image) {}
	private record Marker(BlockPos origin, net.minecraft.world.level.block.Rotation rotation) {}
	/** Where the survival beat plays: the player's feet and the foot of the tree it chops, on level ground outside the door. */
	private record Lawn(Vec3 feet, BlockPos tree) {}

	private record Shot(String name, java.util.function.Consumer<Minecraft> play) {}
	private record Cue(int frame, Runnable run) {}
	/** What a rig wants for this moment of the shot: {eye x, y, z, aim x, y, z}. */
	private interface Rig { double[] want(double seconds, Vec3 player); }

	private final T3CraftClient mod;
	private final Path out;
	private final String place = System.getProperty("t3craft.demoPlace", "Lakeside");
	private final String ffmpeg = System.getProperty("t3craft.demoFfmpeg", "ffmpeg");
	/** The fixture thread that asks for approval mid-video, and the finished one the player types to. */
	private final String staged = System.getProperty("t3craft.demoThread", "office-4");
	private final String typedTo = System.getProperty("t3craft.demoTypeThread", "office-5");
	private final long seed = Long.getLong("t3craft.demoSeed", 20261111L);
	private static final String TYPED = "ship it";
	private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(6);
	private final List<Segment> segments = new ArrayList<>();
	private final List<Shot> shots = new ArrayList<>();
	private final List<Cue> cues = new ArrayList<>();
	private final DemoOverlay overlay = new DemoOverlay();
	private Step step = Step.TITLE;
	private int t, deadline = 20 * 60 * 20, shot = -1, phase, phaseAt, waited;
	private OfficeFrame frame;
	private java.util.concurrent.CompletableFuture<Marker> reading;

	// Frame clock and capture (render thread).
	private boolean fixed;
	private double virtualMs = -1;
	private volatile Segment segment;
	private int frames, zero;
	private String broken;
	private final StringBuilder looks = new StringBuilder("shot,frame,yaw,pitch,x,y,z\n"), cameras = new StringBuilder("shot,frame,x,y,z,yaw,pitch,playerY\n");

	// Camera and player.
	private View view = View.CAMERA;
	private Entity cam;
	private Rig rig;
	private double eyeLag, aimLag;
	private final DemoHand.Operator eyeOp = new DemoHand.Operator(3), aimOp = new DemoHand.Operator(3);
	private int poseFrame = -1;
	private double[] pose;
	private DemoHand.Look hand;
	private final Keys keys = new Keys();
	private Walk walk;
	private Random random = new Random(seed);

	// Scene state.
	private OfficeBrain.Agent waiting, typist;
	private Vec3 hookAlong, hookRow;
	private Lawn lawn;
	private double[] stand;
	private float aimYaw, aimPitch;
	private AgentTalk.Conversation conversation;
	private AgentTalk.Phase lastPhase;
	private double[] talkMid;
	private double talkAngle, talkFloor, firstSec, secondSec, mark1, mark2;
	private int talkSide = 1;
	private boolean asked, delegated, doorDone, flag, hudOn, hudHidden;
	private float pointerX, pointerY;
	private double levelY = Double.NaN;
	private Vec3 hookOut;
	private BlockPos doorFirst;
	private DemoHand.Look pointer;
	private String hookStretch;
	private final List<Entity> posed = new ArrayList<>();

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
			// A retake of some shots keeps the marks of the others.
			recorder.keep("marks.txt", " ", false);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		recorder.hooks();
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
		frames++;
		overlay.time(now());
		try {
			eachFrame(mc);
		} catch (RuntimeException e) {
			T3CraftClient.LOGGER.error("DEMO error", e);
			broken = e.toString();
		}
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
		zero = frames;
		mark("start");
	}

	private void end() {
		Segment s = segment;
		if (s == null) return;
		mark("end");
		segment = null;
		s.ended = true;
	}

	/**
	 * Rewrites a per-shot file of the last recording without the lines of the shots this run records (all of them,
	 * unless {@code -Dt3craft.demoShots} names some), so a retake of a few shots leaves the rest in place.
	 */
	private List<String> keep(String file, String separator, boolean header) throws IOException {
		Path path = out.resolve(file);
		List<String> kept = new ArrayList<>();
		String only = System.getProperty("t3craft.demoShots", "");
		if (!only.isBlank() && Files.exists(path)) {
			List<String> retaken = List.of(only.split(","));
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			for (String line : header ? lines.subList(Math.min(1, lines.size()), lines.size()) : lines) if (!retaken.contains(line.split(separator)[0])) kept.add(line);
		}
		if (!header) Files.writeString(path, kept.isEmpty() ? "" : String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
		return kept;
	}

	/** Notes the current frame of the open segment under {@code label}, for the edit. */
	private void mark(String label) {
		if (segment != null) mark(label, segment.requested);
	}

	/** Notes a number about the open segment under {@code label}: where something is in the frame, for the edit. */
	private void mark(String label, int value) {
		Segment s = segment;
		if (s == null) return;
		String line = s.name + " " + label + " " + value + "\n";
		T3CraftClient.LOGGER.info("DEMO mark {}", line.strip());
		try {
			Files.writeString(out.resolve("marks.txt"), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			T3CraftClient.LOGGER.warn("DEMO could not write marks", e);
		}
	}

	// ---- Every frame: cues, the player's view, the camera ----

	/** Seconds on the recording clock. */
	private double now() { return frames / (double) FPS; }

	/** Seconds since the open segment began recording; 0 before it does. */
	private double rec() { return segment == null ? 0 : (frames - zero) / (double) FPS; }

	/** Runs {@code run} in {@code seconds}, on the frame it falls in: a key or a glance does not wait for a game tick. */
	private void after(double seconds, Runnable run) {
		cues.add(new Cue(frames + Math.max(1, (int) Math.round(seconds * FPS)), run));
	}

	private void eachFrame(Minecraft mc) {
		for (Cue cue : List.copyOf(cues)) {
			if (cue.frame() > frames) continue;
			cues.remove(cue);
			cue.run().run();
		}
		Screen screen = mc.gui.screen();
		if (pointer != null) {
			if (screen != null && !(screen instanceof Quiet)) {
				// The game hands these to the screen as the mouse position, so what the pointer is over lights up.
				double[] at = pointer.step(1.0 / FPS);
				float x = Math.round(at[0] * 2.5) * 4, y = Math.round(at[1] * 2.5) * 4;
				pointerX = x;
				pointerY = y;
				overlay.pointer(x, y);
				var window = mc.getWindow();
				mc.mouseHandler.onMove(window.handle(), x * window.getScreenWidth() / 1920.0, y * window.getScreenHeight() / 1080.0, 0, 0);
			} else {
				pointer = null;
				overlay.pointer(Float.NaN, 0);
			}
		}
		var p = mc.player;
		if (p == null || hand == null || view == View.CAMERA) return;
		// With a screen open the game does not turn the view, and neither does this.
		if (screen == null || screen instanceof Quiet) {
			double[] at = hand.step(1.0 / FPS);
			float yaw = (float) at[0], pitch = (float) at[1];
			p.setYRot(yaw);
			p.setXRot(pitch);
			p.setYHeadRot(yaw);
			p.yRotO = yaw;
			p.xRotO = pitch;
			p.yHeadRotO = yaw;
		}
		if (segment != null) looks.append(String.format(Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f%n", segment.name, frames - zero, p.getYRot(), p.getXRot(), p.getX(), p.getY(), p.getZ()));
	}

	private double[] pose(float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (rig == null || view == View.FIRST || mc.player == null || step != Step.SHOTS) return null;
		if (poseFrame != frames || pose == null) {
			poseFrame = frames;
			// The operator follows where the player is on the ground, not its head: a jump or a crouch moves the player in
			// the frame and leaves the camera level (a step up or down is taken slowly).
			Vec3 feet = mc.player.getPosition(partial);
			if (Double.isNaN(levelY)) levelY = feet.y;
			else if (mc.player.onGround()) levelY += (feet.y - levelY) * (1 - Math.exp(-(1.0 / FPS) / 0.4));
			double[] want = rig.want(rec(), new Vec3(feet.x, levelY, feet.z));
			double[] eye = eyeOp.follow(new double[] {want[0], want[1], want[2]}, eyeLag, 1.0 / FPS);
			double[] aim = aimOp.follow(new double[] {want[3], want[4], want[5]}, aimLag, 1.0 / FPS);
			Vec3 from = new Vec3(eye[0], eye[1], eye[2]), at = new Vec3(aim[0], aim[1], aim[2]);
			pose = new double[] {from.x, from.y, from.z, yawTo(from, at), pitchTo(from, at)};
			if (segment != null) cameras.append(String.format(Locale.ROOT, "%s,%d,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f%n", segment.name, frames - zero, pose[0], pose[1], pose[2], pose[3], pose[4], feet.y));
		}
		return pose;
	}

	/** Sets the rig of this shot; the operator trails the wanted eye and aim by about {@code eyeLag} and {@code aimLag} seconds. */
	private void rig(Rig rig, double eyeLag, double aimLag) {
		this.rig = rig;
		this.eyeLag = eyeLag;
		this.aimLag = aimLag;
		eyeOp.cut();
		aimOp.cut();
		pose = null;
		levelY = Double.NaN;
	}

	private static double[] six(Vec3 eye, Vec3 aim) { return new double[] {eye.x, eye.y, eye.z, aim.x, aim.y, aim.z}; }

	// ---- Places ----

	/** Design-relative point, turned with the office. */
	private Vec3 d(double x, double y, double z) { return frame.point(x, y, z); }

	/** A design direction as a world one. */
	private Vec3 dir(double x, double z) { return d(x, 0, z).subtract(d(0, 0, 0)); }

	/** A world position in the design's own {x, z}. */
	private double[] local(Vec3 world) {
		Vec3 from = world.subtract(d(0, 0, 0));
		return new double[] {from.dot(dir(1, 0)), from.dot(dir(0, 1))};
	}

	private static double smooth(double k) {
		k = Mth.clamp(k, 0, 1);
		return k * k * (3 - 2 * k);
	}

	private static Vec3 mix(Vec3 a, Vec3 b, double k) { return a.add(b.subtract(a).scale(k)); }

	private static double flat(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }

	private static float yawTo(Vec3 from, Vec3 at) { return (float) Math.toDegrees(Math.atan2(at.z - from.z, at.x - from.x)) - 90; }

	private static float pitchTo(Vec3 from, Vec3 at) { return (float) -Math.toDegrees(Math.atan2(at.y - from.y, Math.hypot(at.x - from.x, at.z - from.z))); }

	private static String name(Minecraft mc) { return mc.player.getName().getString(); }

	// ---- Who holds the camera ----

	/** A cinematic shot: an unseen operator. The player is a spectator parked above the roof, so no agent looks at it. */
	private void camera(Minecraft mc, int fov) {
		command(mc, "gamemode spectator " + name(mc));
		Vec3 park = d(15, 34, 12);
		command(mc, String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", name(mc), park.x, park.y, park.z));
		view = View.CAMERA;
		hand = null;
		walk = null;
		keys.release();
		mc.player.input = new net.minecraft.client.player.KeyboardInput(mc.options);
		if (cam == null || cam.isRemoved() || cam.level() != mc.level) {
			cam = net.minecraft.world.entity.EntityTypes.MARKER.create(mc.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			cam.setId(-7900001);
			mc.level.addEntity(cam);
		}
		// With a camera that is not a player the game draws no hotbar, hearts or hand; name tags and toasts stay.
		mc.setCameraEntity(cam);
		mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		mc.options.fov().set(fov);
		mc.options.bobView().set(false);
		if (mc.gui.screen() != null) mc.gui.setScreen(null);
		hudOn = false;
	}

	/**
	 * A shot with the player in it: a survival player standing at {@code feet}, either seen through its own eyes or
	 * filmed (the game's own third person, with the camera placed by the rig). Its view belongs to {@link #hand}.
	 */
	private void player(Minecraft mc, View how, int fov, Vec3 feet, float yaw, float pitch) {
		view = how;
		command(mc, "gamemode survival " + name(mc));
		command(mc, String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f %.2f %.2f", name(mc), feet.x, feet.y, feet.z, Mth.wrapDegrees(yaw), pitch));
		mc.setCameraEntity(mc.player);
		mc.options.setCameraType(how == View.FIRST ? net.minecraft.client.CameraType.FIRST_PERSON : net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		mc.options.fov().set(fov);
		mc.player.setDeltaMovement(Vec3.ZERO);
		mc.player.getInventory().setSelectedSlot(0);
		keys.release();
		mc.player.input = keys;
		walk = null;
		hand = new DemoHand.Look(random.nextLong(), yaw, pitch);
		if (mc.gui.screen() != null) mc.gui.setScreen(null);
		mc.gui.hud.getChat().clearMessages(false);
		// Hotbar and hearts belong to the player's own eyes; a filmed shot has no interface in it (name tags and toasts stay).
		hudOn = how == View.FIRST;
		// View bobbing is what the player sees through its own eyes; the game would apply it to a filmed shot too, and an operator's camera does not step.
		mc.options.bobView().set(how == View.FIRST);
	}

	/** The keys the script holds down; the game reads them exactly as it reads the keyboard. */
	private static final class Keys extends net.minecraft.client.player.ClientInput {
		boolean forward, back, left, right, sneak, sprint;
		int jump;

		@Override
		public void tick() {
			keyPresses = new net.minecraft.world.entity.player.Input(forward, back, left, right, jump > 0, sneak, sprint);
			if (jump > 0) jump--;
			moveVector = new net.minecraft.world.phys.Vec2((left ? 1 : 0) - (right ? 1 : 0), (forward ? 1 : 0) - (back ? 1 : 0)).normalized();
		}

		void release() {
			forward = back = left = right = sneak = sprint = false;
			jump = 0;
		}
	}

	/**
	 * Walking somewhere the way a person does: looking at a point a few blocks further along the route (so corners are
	 * cut and the path bends), holding forward, sprinting on the straights, letting go of sprint and then of forward
	 * before the mark and sliding the rest. With something to {@link #face}, the last blocks are walked looking at it,
	 * sideways or backwards if need be, so the player arrives turned to it instead of stopping and then swivelling.
	 */
	private final class Walk {
		final List<Vec3> path;
		final boolean sprint;
		final double pitch;
		/** What to be looking at on arrival, or null. */
		Vec3 face;
		/** Whether to jump what is in the way: a ledge, a desk. Never a door. */
		boolean hops = true;
		int leg;
		boolean arrived, turned;

		Walk(boolean sprint, double pitch, Vec3... path) {
			this.sprint = sprint;
			this.pitch = pitch;
			this.path = List.of(path);
			hand.follow(2.5);
		}

		void tick(Minecraft mc) {
			var p = mc.player;
			Vec3 at = p.position();
			while (leg < path.size() - 1 && flat(at, path.get(leg)) < 1.1) leg++;
			double left = flat(at, path.get(leg)), remaining = left;
			for (int i = leg; i < path.size() - 1; i++) remaining += flat(path.get(i), path.get(i + 1));
			// The point the feet head for: a few blocks further along the route.
			double ahead = sprint ? 3.0 : 2.0;
			Vec3 from = at, aim = path.getLast();
			for (int i = leg; i < path.size(); i++) {
				double length = flat(from, path.get(i));
				if (ahead <= length || i == path.size() - 1) { aim = mix(from, path.get(i), length < 1e-6 ? 1 : Math.min(1, ahead / length)); break; }
				ahead -= length;
				from = path.get(i);
			}
			float heading = yawTo(at, aim);
			boolean facing = face != null && remaining < 3.4;
			if (facing) {
				// One turn of the head to it, a few strides out, then the eyes stay on it.
				Vec3 eyes = p.getEyePosition();
				if (!turned) { turned = true; hand.turnTo(yawTo(eyes, face), pitchTo(eyes, face), 3); }
				else if (!hand.moving()) { hand.follow(2.5); hand.aim(yawTo(eyes, face), pitchTo(eyes, face), 0, 0); }
			} else if (flat(at, aim) > 0.4) hand.aim(heading, pitch, 0, 0);
			double off = Mth.wrapDegrees(heading - p.getYRot()), away = Math.abs(off), speed = p.getDeltaMovement().horizontalDistance();
			if (remaining < 0.3 + speed * 1.6) {
				keys.forward = keys.back = keys.left = keys.right = keys.sprint = false;
				if (speed < 0.03) arrived = true;
				return;
			}
			if (facing) {
				// The eyes are on the target; the keys carry the feet to the mark.
				keys.forward = away < 67.5;
				keys.back = away > 112.5;
				keys.left = off < -22.5 && off > -157.5;
				keys.right = off > 22.5 && off < 157.5;
				keys.sprint = false;
			} else {
				keys.forward = away < 80;
				keys.back = keys.left = keys.right = false;
				keys.sprint = sprint && away < 55 && remaining > 2.6;
			}
			if (!hops || !p.onGround()) return;
			// A ledge the stride does not clear: jump it. A desk in the way of a sprint: jump before reaching it.
			if (p.horizontalCollision) keys.jump = 2;
			else if (keys.sprint && speed > 0.2) {
				Vec3 v = p.getDeltaMovement();
				BlockPos block = BlockPos.containing(at.x + v.x / speed * 1.3, at.y + 0.1, at.z + v.z / speed * 1.3);
				var state = mc.level.getBlockState(block);
				var shape = state.getCollisionShape(mc.level, block);
				if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > 0.6 && !(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
					&& mc.level.getBlockState(block.above()).getCollisionShape(mc.level, block.above()).isEmpty()) keys.jump = 2;
			}
		}
	}

	private static void swing(Minecraft mc) {
		mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
	}

	/** The use key: the game does the rest (opening a door, an agent's card) from what the crosshair is on. */
	private static void use(Minecraft mc) {
		net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
	}

	/** Holding the attack button on the log under the crosshair. */
	private static void mine(Minecraft mc) {
		if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
			&& mc.level.getBlockState(hit.getBlockPos()).is(net.minecraft.tags.BlockTags.LOGS)) {
			if (mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) swing(mc);
		}
	}

	/** Has one agent do something on cue for a moment (the office's own pose, held through its dev hook): a stretch, arms up. */
	private void act(Minecraft mc, String threadId, AgentLife.Activity activity, double seconds) {
		for (var entry : mod.residents().threadsByEntity().entrySet()) {
			Entity agent = entry.getValue().equals(threadId) ? mc.level.getEntity(entry.getKey()) : null;
			if (agent == null) continue;
			AgentLife.hold(agent, activity);
			posed.add(agent);
			after(seconds, () -> AgentLife.hold(agent, null));
		}
	}

	/** Puts an agent in one of the office's poses until {@link #release} or the end of the shot: waving, nodding, talking, typing. */
	private void hold(Minecraft mc, String threadId, AgentLife.Activity activity) {
		for (var entry : mod.residents().threadsByEntity().entrySet()) {
			Entity agent = entry.getValue().equals(threadId) ? mc.level.getEntity(entry.getKey()) : null;
			if (agent == null) continue;
			AgentLife.hold(agent, activity);
			if (!posed.contains(agent)) posed.add(agent);
		}
	}

	private void release() {
		for (Entity agent : posed) AgentLife.hold(agent, null);
		posed.clear();
	}

	private boolean onAgent(Minecraft mc, OfficeBrain.Agent a) {
		return mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && a.threadId.equals(mod.residents().threadsByEntity().get(hit.getEntity().getId()));
	}

	// ---- Script ----

	void tick(Minecraft mc) {
		if (step == Step.DONE) return;
		if (broken != null) { finish(mc, "FAIL " + broken); return; }
		if (--deadline == 0) { finish(mc, "FAIL timed out at " + step + (step == Step.SHOTS ? " " + shots.get(shot).name() : "")); return; }
		try {
			t++;
			script(mc);
			// Command feedback ("Your game mode has been updated") never belongs in a frame.
			if (fixed && mc.player != null) mc.gui.hud.getChat().clearMessages(false);
			if (step == Step.SHOTS && view == View.CAMERA && cam != null && pose != null) cam.setPos(pose[0], pose[1], pose[2]);
			// The teleport that opens a shot has landed by now: the operator starts from where the subject really is.
			if (step == Step.SHOTS && t == 7 && segment == null) { eyeOp.cut(); aimOp.cut(); levelY = Double.NaN; }
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

	/** The next shot: everything on screen leaves with the cut. */
	private void next(Minecraft mc) {
		end();
		cues.clear();
		overlay.clear();
		keys.release();
		walk = null;
		release();
		pointer = null;
		hudHidden = false;
		mark1 = mark2 = 0;
		mc.options.particles().set(net.minecraft.server.level.ParticleStatus.ALL);
		shot++;
		t = phase = phaseAt = waited = 0;
		flag = false;
		if (shot >= shots.size()) { go(Step.FINISH); return; }
		random = new Random(seed ^ shots.get(shot).name().hashCode());
		T3CraftClient.LOGGER.info("DEMO shot {}", shots.get(shot).name());
	}

	private void phase(int next) {
		phase = next;
		phaseAt = t;
	}

	/** Holds a shot back until what it films is there; {@code stage} is tried once to make it so. Fails the take after 45 s. */
	private boolean ready(Minecraft mc, boolean there, Runnable stage, String what) {
		if (there) return true;
		if (waited++ == 0 && stage != null) stage.run();
		if (waited > 20 * 45) finish(mc, "FAIL " + what);
		t = 0;
		return false;
	}

	private void clock(Minecraft mc, int time) { command(mc, "time set " + time); }

	private void hooks() {
		shots.addAll(List.of(new Shot("hook", this::hook), new Shot("chop", this::chop), new Shot("ping", this::ping), new Shot("dash", this::dash),
			new Shot("burst", this::burst), new Shot("floor", this::floor), new Shot("row", this::row), new Shot("arrive", this::arrive), new Shot("card", this::card),
			new Shot("desk", this::desk), new Shot("talk", this::talk), new Shot("ship", this::ship), new Shot("cheer", this::cheer), new Shot("end", this::title)));
		String only = System.getProperty("t3craft.demoShots", "");
		if (!only.isBlank()) {
			List<String> names = List.of(only.split(","));
			shots.removeIf(s -> !names.contains(s.name()));
			if (shots.isEmpty()) throw new IllegalStateException("-Dt3craft.demoShots names no shot: " + only);
		}
		// The text: over the HUD in the world, and over whatever screen is open.
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath(T3CraftClient.MOD_ID, "demo"),
			(graphics, delta) -> { if (Minecraft.getInstance().gui.screen() == null) overlay.draw(graphics); });
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> overlay.draw(graphics)));
	}

	private void script(Minecraft mc) {
		Screen screen = mc.gui.screen();
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
			case SETTLE -> settle(mc);
			case SHOTS -> shots.get(shot).play().accept(mc);
			case FINISH -> {
				if (segments.stream().allMatch(s -> s.done)) finish(mc, "PASS " + segments.stream().map(s -> s.name + "=" + s.requested).toList());
				else if (t > 20 * 120) finish(mc, "FAIL encoders did not finish");
			}
			default -> {}
		}
	}

	/** Sets the stage: the kit, the tree, the agents at their places. */
	private void settle(Minecraft mc) {
		if (t == 1) {
			mc.options.pauseOnLostFocus = false;
			mc.options.bobView().set(true);
			// The script presses jump itself where a person would; the game's auto-jump would hop at any furniture brushed in passing.
			mc.options.autoJump().set(false);
			mc.options.guiScale().set(4);
			mc.resizeGui();
			String who = name(mc);
			// The survival kit goes in now, with every recipe known, so its advancement and recipe toasts are long gone by the first shot.
			// The yellow jacket and the axe are how the player is told from the agents, who are player-shaped too (record.sh also picks the Steve skin).
			for (String command : List.of("difficulty peaceful", "weather clear", "time set 7000", "recipe give " + who + " *",
				"item replace entity " + who + " hotbar.0 with stone_axe", "item replace entity " + who + " hotbar.1 with iron_pickaxe",
				"item replace entity " + who + " hotbar.2 with iron_sword", "item replace entity " + who + " hotbar.3 with torch 32",
				"item replace entity " + who + " hotbar.4 with cooked_beef 12", "item replace entity " + who + " hotbar.5 with oak_planks 41",
				"item replace entity " + who + " hotbar.6 with cobblestone 64", "item replace entity " + who + " hotbar.7 with water_bucket",
				"item replace entity " + who + " hotbar.8 with bread 5", "item replace entity " + who + " armor.chest with leather_chestplate[dyed_color=16763904]")) command(mc, command);
			// The corner status follows the thread that will ask for the approval, and its ping is one the player gets.
			mod.focus(staged);
			// The corner status line is off for the whole video (it is small print nobody is told about), and the hotbar,
			// hearts and the rest of the interface show only through the player's own eyes.
			var elements = new ArrayList<>(List.of(net.minecraft.resources.Identifier.fromNamespaceAndPath(T3CraftClient.MOD_ID, "status")));
			var vanilla = net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.class;
			for (String name : List.of("HOTBAR", "ARMOR_BAR", "HEALTH_BAR", "FOOD_BAR", "AIR_BAR", "MOUNT_HEALTH", "INFO_BAR", "EXPERIENCE_LEVEL", "INFO_BAR_FOREGROUND", "HELD_ITEM_TOOLTIP", "CROSSHAIR")) {
				try { elements.add((net.minecraft.resources.Identifier) vanilla.getField(name).get(null)); } catch (ReflectiveOperationException e) { T3CraftClient.LOGGER.warn("DEMO no interface element {}", name); }
			}
			for (var id : elements) {
				boolean status = id.getNamespace().equals(T3CraftClient.MOD_ID);
				try {
					net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(id, element -> (graphics, delta) -> { if (hudOn && !hudHidden && !status) element.extractRenderState(graphics, delta); });
				} catch (RuntimeException e) {
					T3CraftClient.LOGGER.warn("DEMO the interface element {} stays on in every shot: {}", id, e.toString());
				}
			}
			fixed = true;
		}
		if (t == 3) camera(mc, 58);
		var agents = mod.residents().brainForTest().agents();
		waiting = agents.get(staged);
		typist = agents.get(typedTo);
		if (t == 40) {
			int[] door = OfficeDesign.doorOutside(), e = OfficeDesign.EXTENT;
			lawn = surveyLawn(mc, door, e);
			if (lawn == null) { finish(mc, "FAIL no level ground outside the office door for the survival beat"); return; }
			BlockPos tree = lawn.tree();
			int x = tree.getX(), y = tree.getY(), z = tree.getZ();
			for (String command : List.of(
				"fill " + (x - 2) + " " + (y + 3) + " " + (z - 2) + " " + (x + 2) + " " + (y + 4) + " " + (z + 2) + " oak_leaves[persistent=true] replace air",
				"fill " + (x - 1) + " " + (y + 5) + " " + (z - 1) + " " + (x + 1) + " " + (y + 6) + " " + (z + 1) + " oak_leaves[persistent=true] replace air",
				"setblock " + x + " " + (y - 1) + " " + z + " dirt", "fill " + x + " " + y + " " + z + " " + x + " " + (y + 4) + " " + z + " oak_log")) command(mc, command);
		}
		boolean seated = !agents.isEmpty() && agents.values().stream().allMatch(OfficeBrain.Agent::settled);
		if (t < 300 || (!seated && t < 20 * 75)) return;
		if (waiting == null || waiting.status != T3State.Status.WORKING || waiting.zone != OfficeBrain.Zone.WORK)
			{ finish(mc, "FAIL the staged thread " + staged + " is not working at a desk; is the fixture running with --demo?"); return; }
		if (typist == null || typist.status != T3State.Status.DONE || typist.floor != 0) { finish(mc, "FAIL the thread to type to (" + typedTo + ") is not a finished agent on the ground floor"); return; }
		if (!frameHook(agents.values())) { finish(mc, "FAIL fewer than three agents are working side by side at the first bench row for the opening shot"); return; }
		T3CraftClient.LOGGER.info("DEMO office {} rotation {}, {} agents, all seated {}", frame.origin().toShortString(), frame.rotation(), agents.size(), seated);
		go(Step.SHOTS);
		shot = -1;
		next(mc);
	}

	// ---- Shots ----
	// Each is called every game tick while it is the current one, with t counting from 1, and calls next() when it is over.
	// Framing numbers are design coordinates (x, height above the floor, z) and blocks; times are seconds.

	/** Across the bench onto the first row from the front: three agents, their tags apart, the middle one stretching. Frame 0 is the thumbnail. */
	private void hook(Minecraft mc) {
		if (t == 1) {
			camera(mc, 58);
			clock(mc, 7000);
			Vec3 mid = hookRow, along = hookAlong, out = hookOut;
			// A short drift onto the pose it holds, then a creep: never quite still, and no tag near the edge.
			rig((s, p) -> {
				double x = 0.35 - 0.2 * (1 - Math.exp(-s / 0.5)) - 0.02 * s;
				return six(mid.add(along.scale(x + 0.5)).add(out.scale(3.3)).add(0, 1.75, 0), mid.add(along.scale(x)));
			}, 0, 0);
		}
		if (t == 6) mc.gui.toastManager().clear();
		if (t == 8) {
			begin("hook");
			// The sentence is whole on the first frame; only its last word arrives.
			overlay.caption(now() - 3, 0.15, 860, true, "Your coding agents", "live in *Minecraft.");
			overlay.keyWord(now() + 0.25);
			// The one in the middle leans back and stretches.
			after(0.9, () -> act(mc, hookStretch, AgentLife.Activity.STRETCH, 1.4));
		}
		if (segment != null && rec() >= 3.0) next(mc);
	}

	/** Filmed from beyond the tree, low: the player walks up to an oak and chops; the office stands behind. */
	private void chop(Minecraft mc) {
		Vec3 feet = lawn.feet(), trunk = Vec3.atBottomCenterOf(lawn.tree()), away = new Vec3(trunk.x - feet.x, 0, trunk.z - feet.z).normalize(), right = new Vec3(-away.z, 0, away.x);
		BlockPos upper = lawn.tree().above();
		if (t == 1) {
			clock(mc, 7300);
			player(mc, View.THIRD, 62, feet.subtract(away.scale(1.6)), yawTo(feet, trunk), 6);
			Vec3 eye = feet.add(away.scale(2.3)).add(right.scale(2.5)).add(0, 1.0, 0);
			// The player on the right third, clear of the caption.
			rig((s, p) -> six(eye.subtract(away.scale(0.25 * s)), p.add(right.scale(1.5)).add(0, 1.3, 0)), 0, 0.3);
		}
		if (t == 6) {
			mc.gui.setScreen(new Quiet());
			walk = new Walk(false, 4, feet);
		}
		if (t == 9) {
			mc.gui.toastManager().clear();
			begin("chop");
			overlay.caption(now() + 0.03, "You keep *playing.");
		}
		if (walk != null) {
			walk.tick(mc);
			if (walk.arrived || mc.player.position().distanceTo(feet) < 0.25) {
				walk = null;
				keys.release();
				lookAt(mc, Vec3.atCenterOf(upper), 1.2);
			}
		} else if (t > 6) {
			mine(mc);
			if (phase == 0 && mc.level.getBlockState(upper).isAir()) {
				mark("log");
				phase(1);
				after(DemoHand.wait(random, 0.3, 0.2), () -> lookAt(mc, new Vec3(trunk.x, trunk.y + 0.6, trunk.z), 1.5));
			}
		}
		if (t > 20 * 12) { finish(mc, "FAIL the log did not break; the player is not chopping"); return; }
		if (phase == 1 && now() >= overlay.captionShown() + 1.4) next(mc);
	}

	/**
	 * Through the player's eyes at the same tree: the staged thread asks for its approval and the Needs-you toast slides
	 * in mid-log. The player reads it with its eyes, still chopping, finishes the log, turns round and runs for the office.
	 */
	private void ping(Minecraft mc) {
		Vec3 feet = lawn.feet(), eyes = feet.add(0, 1.62, 0), trunk = Vec3.atBottomCenterOf(lawn.tree());
		Vec3 low = new Vec3(trunk.x, trunk.y + 0.6, trunk.z), high = new Vec3(trunk.x, trunk.y + 2.4, trunk.z);
		BlockPos second = lawn.tree().above(2);
		if (t == 1) {
			clock(mc, 7350);
			BlockPos upper = lawn.tree().above();
			command(mc, "setblock " + upper.getX() + " " + upper.getY() + " " + upper.getZ() + " air");
			player(mc, View.FIRST, 62, feet, yawTo(eyes, low), pitchTo(eyes, low));
		}
		if (t == 6) mc.gui.setScreen(new Quiet());
		if (t == 20) mc.gui.toastManager().clear();
		// The axe is up and swinging before the shot begins.
		if (t == 27) {
			begin("ping");
			after(0.33, () -> { fixture("ask?id=" + staged); asked = true; });
		}
		if (t >= 24 && phase <= 1) {
			mine(mc);
			// The lower log gives way; on to the next one up.
			if (!flag && mc.level.getBlockState(lawn.tree()).isAir()) {
				flag = true;
				mark("log");
				after(DemoHand.wait(random, 0.3, 0.15), () -> { if (phase <= 1) lookAt(mc, high, 1.5); });
			}
		}
		var shown = mc.gui.toastManager().getToast(net.minecraft.client.gui.components.toasts.SystemToast.class, T3CraftClient.TOAST);
		if (phase == 0 && segment != null && status(staged) == T3State.Status.NEEDS_YOU && shown != null) {
			mark("ping");
			phase(1);
			// The toast is the moment of the video and too small to read on a phone: the recorder draws the game's own toast
			// itself, so it can slide in at its corner, grow to the middle while it is read, and go back, as one object.
			mc.gui.toastManager().clear();
			overlay.toast(shown, now());
			overlay.toastGrow(now() + 0.62);
			mark1 = rec() + 0.6 + DemoHand.wait(random, 0.85, 0.08);
		}
		if (phase == 1 && rec() >= mark1 && (mc.level.getBlockState(second).isAir() || rec() >= mark1 + 0.4)) {
			phase(2);
			mc.gameMode.stopDestroyBlock();
			mark("stop");
			after(DemoHand.wait(random, 0.13, 0.2), () -> {
				Vec3 door = d(26.0, 0, -2.2);
				hand.turnTo(yawTo(mc.player.position(), door), 3, 2);
				double turn = hand.settles() - hand.time();
				mark("turn");
				phase(3);
				// Read: the toast goes back to its corner as the player turns, and the office comes into view undimmed.
				overlay.toastBack(now() + 0.05);
				// The feet start before the turn is over.
				after(turn * 0.5, () -> keys.forward = keys.sprint = true);
				after(turn + 0.04, () -> walk = new Walk(true, 3, door));
				mark2 = rec() + turn - 0.1;
			});
		}
		if (walk != null) walk.tick(mc);
		if (segment != null && phase == 0 && rec() > 6) { finish(mc, "FAIL the Needs-you toast for " + staged + " never showed (status " + status(staged) + ")"); return; }
		if (phase == 3 && rec() >= mark2) next(mc);
	}

	/** A chase camera behind the player sprinting across the lawn, jumping, opening both door leaves and running in. */
	private void dash(Minecraft mc) {
		Vec3 feet = lawn.feet(), door = d(26.0, 0, -2.2);
		if (t == 1) {
			clock(mc, 7400);
			Vec3 ahead = new Vec3(door.x - feet.x, 0, door.z - feet.z).normalize(), right = new Vec3(-ahead.z, 0, ahead.x);
			// The run started at the tree; the camera picks it up a few strides on, clear of the leaves.
			player(mc, View.THIRD, 62, feet.add(ahead.scale(2.5)), yawTo(feet, door), 3);
			rig((s, p) -> six(p.subtract(ahead.scale(3.4)).add(right.scale(0.45)).add(0, 1.55, 0), p.add(ahead.scale(3.5)).add(0, 1.6, 0)), 0.2, 0.12);
			doorFirst = null;
			doorDone = false;
		}
		if (t == 8) walk = new Walk(true, 3, d(26.0, 0, -5.5), door, d(26.0, 0, 1.2), d(25.6, 0, 5.0));
		double[] at = local(mc.player.position());
		if (walk != null) {
			// Terrain may be jumped; the door never.
			walk.hops = at[1] < -4.5;
			walk.tick(mc);
		}
		if (segment == null && t > 8 && flat(mc.player.position(), feet) > 13.2) {
			mc.gui.toastManager().clear();
			begin("dash");
			// A sprint-jump on the way, as people do.
			after(DemoHand.wait(random, 0.3, 0.1), () -> { if (mc.player.onGround() && local(mc.player.position())[1] < -5.5) keys.jump = 2; });
		}
		door(mc);
		if (segment != null && doorway(mc, at)) return;
		if (segment != null && at[1] > 0.1) next(mc);
		else if (t > 20 * 12) finish(mc, "FAIL the player did not get through the office door (at " + java.util.Arrays.toString(at) + ")");
	}

	/** Right-clicks the two door leaves, one after the other, once they are within reach. */
	private void door(Minecraft mc) {
		if (doorDone || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
		var state = mc.level.getBlockState(hit.getBlockPos());
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) || mc.player.getEyePosition().distanceTo(hit.getLocation()) > 4.0) return;
		BlockPos leaf = state.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? hit.getBlockPos() : hit.getBlockPos().below();
		if (leaf.equals(doorFirst)) return;
		if (!state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) { use(mc); mark("door"); }
		if (doorFirst != null) { doorDone = true; hand.follow(2.5); return; }
		doorFirst = leaf;
		// The other leaf, a moment later.
		BlockPos left = frame.block(25, 0, -1), other = leaf.equals(left) ? frame.block(26, 0, -1) : left;
		after(DemoHand.wait(random, 0.07, 0.2), () -> lookAt(mc, new Vec3(other.getX() + 0.5, other.getY() + 1.35, other.getZ() + 0.5), 1.5));
		after(0.45, () -> { if (!doorDone) { doorDone = true; hand.follow(2.5); } });
	}

	/** Fails the take if the player is held up in the doorway; true if it did. */
	private boolean doorway(Minecraft mc, double[] at) {
		double speed = mc.player.getDeltaMovement().horizontalDistance() * 20;
		if (at[1] < -1.6 || at[1] > 0.4 || speed >= 3) return false;
		finish(mc, "FAIL the player was held up in the doorway (" + String.format(Locale.ROOT, "%.1f", speed) + " blocks a second at z " + String.format(Locale.ROOT, "%.1f", at[1]) + ")");
		return true;
	}

	/** A camera standing inside: the player comes in past the usage board on the wall, vaults the end of the front desk and runs on into the room. */
	private void burst(Minecraft mc) {
		if (t == 1) {
			clock(mc, 7450);
			Vec3 start = d(26.0, 0, -7.5);
			player(mc, View.THIRD, 60, start, yawTo(start, d(26.0, 0, -2)), 2);
			Vec3 eye = d(21.5, 1.5, 11.0), entry = d(27.5, 1.5, 7.4), room = d(8.5, 1.3, 9.5);
			rig((s, p) -> {
				double[] at = local(p);
				Vec3 on = mix(entry, p.add(0, 1.3, 0), smooth((at[1] - 4.5) / 3.5));
				return six(eye, mix(on, room, 0.85 * smooth((20.5 - at[0]) / 4.5)));
			}, 0, 0.3);
			doorFirst = null;
			doorDone = false;
		}
		if (t == 8) walk = new Walk(true, 2, d(26.0, 0, -2.2), d(26.0, 0, 0.8), d(25.4, 0, 3.4), d(24.3, 0, 7.0), d(23.6, 0, 8.5), d(22.2, 0, 8.4), d(21.3, 0, 7.5), d(20.8, 0, 6.7), d(19.3, 0, 6.35), d(8, 0, 6.3));
		double[] at = local(mc.player.position());
		if (walk != null) {
			walk.hops = at[1] > 1.5;
			walk.tick(mc);
		}
		door(mc);
		if (at[1] < 0.6 && t > 8 && doorway(mc, at)) return;
		if (segment == null && t > 8 && at[1] > 2.4) { mc.gui.toastManager().clear(); begin("burst"); }
		if (segment != null && at[0] < 19.0) next(mc);
		else if (t > 20 * 14) finish(mc, "FAIL the player did not make it in and past the front desk (at " + java.util.Arrays.toString(at) + ")");
	}

	/**
	 * The whole floor, held: from the end of the bench, every agent at work on both sides of it, each at its own kind of
	 * desk, while the player sprints through along the far side.
	 */
	private void floor(Minecraft mc) {
		if (t == 1) {
			clock(mc, 7480);
			Vec3 start = d(22.5, 0, 8.3);
			player(mc, View.THIRD, 48, start, yawTo(start, d(21, 0, 6.9)), 2);
			Vec3 eye = d(11.4, 2.75, 7.5), at = d(6.2, 1.2, 3.9), push = at.subtract(eye).normalize();
			rig((s, p) -> six(eye.add(push.scale(0.22 * s)), at), 0, 0);
		}
		if (t == 8) walk = new Walk(true, 2, d(21.3, 0, 7.5), d(20.8, 0, 6.7), d(19.3, 0, 6.35), d(1.6, 0, 6.3));
		if (walk != null) walk.tick(mc);
		double[] at = local(mc.player.position());
		if (segment == null && t > 8 && at[0] < 15.5) {
			mc.gui.toastManager().clear();
			begin("floor");
			// Something going on at the desks: one stretches, one drinks.
			after(0.25, () -> act(mc, "office-2", AgentLife.Activity.STRETCH, 1.5));
			after(0.6, () -> act(mc, "office-8", AgentLife.Activity.SIP, 1.2));
		}
		if (segment != null && rec() >= 1.95) next(mc);
		else if (t > 20 * 12) finish(mc, "FAIL the player did not run past the bench (at " + java.util.Arrays.toString(at) + ")");
	}

	/** Along the second row from the front, over the bench: faces, name tags with their dots, a different desk each, and the player sprinting past behind them. */
	private void row(Minecraft mc) {
		if (t == 1) {
			clock(mc, 7500);
			Vec3 start = d(19.0, 0, 6.4);
			player(mc, View.THIRD, 58, start, yawTo(start, d(10, 0, 6.3)), 2);
			rig((s, p) -> six(d(9.0 - 2.1 * s, 2.25, 3.55), d(8.2 - 2.1 * s, 1.3, 5.5)), 0, 0);
		}
		if (t == 8) walk = new Walk(true, 2, d(16, 0, 6.3), d(1.6, 0, 6.3));
		if (walk != null) walk.tick(mc);
		double[] at = local(mc.player.position());
		if (segment == null && t > 8 && at[0] < 12.2) { mc.gui.toastManager().clear(); begin("row"); }
		if (segment != null && rec() >= 2.05) next(mc);
		else if (t > 20 * 12) finish(mc, "FAIL the player did not run past the bench (at " + java.util.Arrays.toString(at) + ")");
	}

	private boolean atWhiteboard() {
		return waiting.status == T3State.Status.NEEDS_YOU && waiting.zone == OfficeBrain.Zone.NEEDS_YOU && waiting.settled();
	}

	private void ask() {
		if (!asked) fixture("ask?id=" + staged);
		asked = true;
	}

	private Vec3 head(OfficeBrain.Agent a) { return d(a.x, a.y() + 1.45, a.z); }

	/** The player comes up to the agent waving at the whiteboard, turning to it as it arrives, and bobs a hello. */
	private void arrive(Minecraft mc) {
		var a = waiting;
		if (segment == null && phase == 0 && !ready(mc, atWhiteboard(), this::ask, "the agent that needs you never reached the whiteboard (status " + a.status + ", zone " + a.zone + ")")) return;
		if (t == 1) {
			clock(mc, 7550);
			stand = standSpot(mc, a, 0);
			if (stand == null) { finish(mc, "FAIL no clear spot within reach of the waiting agent at " + a.x + ", " + a.z + " to click it from"); return; }
			T3CraftClient.LOGGER.info("DEMO standing at {}, {} for the agent at {}, {}", stand[0], stand[1], a.x, a.z);
			Vec3 start = d(5.9, 0, 7.0);
			player(mc, View.THIRD, 40, start, yawTo(start, d(5.9, 0, 11)), 2);
			// A two-shot in profile from the lounge side, on a long lens: the agent on the left by its whiteboard, the player arriving on the right.
			// Square on to the line between the agent and the player's mark, from the lounge side, so both are the same size.
			double mx = (a.x + stand[0]) / 2, mz = (a.z + stand[1]) / 2, ux = stand[0] - a.x, uz = stand[1] - a.z, length = Math.hypot(ux, uz);
			double px = -uz / length, pz = ux / length, side = pz < 0 ? -1 : 1;
			Vec3 eye = d(Mth.clamp(mx + side * px * 4.3, 1.3, T3Office.WIDTH - 1.5), 1.45, mz + side * pz * 4.3);
			rig((s, p) -> six(eye, mix(head(a), p.add(0, 1.45, 0), 0.5).add(0, -0.25, 0)), 0, 0.3);
		}
		Vec3 spot = d(stand[0], 0, stand[1]);
		if (t == 8) {
			walk = new Walk(true, 3, d(5.9, 0, 10.6), d(6.0, 0, 13.0), spot);
			walk.face = head(a);
			// Open floor all the way: nothing to jump.
			walk.hops = false;
		}
		if (segment == null && t > 8 && local(mc.player.position())[1] > 11.5) { mc.gui.toastManager().clear(); begin("arrive"); }
		if (walk != null) {
			// The last look is squared up while the feet are still sliding.
			if (!flag && walk.turned && flat(mc.player.position(), spot) < 0.9) { flag = true; lookAt(mc, head(a), 1.5); }
			walk.tick(mc);
			if (walk.arrived) {
				walk = null;
				keys.release();
				if (flat(mc.player.position(), spot) > 1.0) { finish(mc, "FAIL the player stopped off its mark in front of the waiting agent"); return; }
				mark("stop");
				// A few quick, uneven bobs, the last one held: the hello every Minecraft player knows.
				double at = DemoHand.wait(random, 0.14, 0.2);
				for (int i = 0, taps = 3; i < taps; i++) {
					after(at, () -> keys.sneak = true);
					at += i == taps - 1 ? 0.2 + random.nextDouble() * 0.06 : 0.1 + random.nextDouble() * 0.1;
					after(at, () -> keys.sneak = false);
					at += 0.1 + random.nextDouble() * 0.12;
				}
				mark2 = rec() + at + 0.05;
				phase(1);
			}
		}
		if (phase == 1 && rec() >= mark2) next(mc);
		else if (t > 20 * 12) finish(mc, "FAIL the player did not reach the waiting agent");
	}

	/** Points {@link #aimYaw}/{@link #aimPitch} at the agent's chest, to its side (still on it) so it stands left of the crosshair and clear of its card. */
	private void aim(OfficeBrain.Agent a, Vec3 eyes) {
		Vec3 chest = d(a.x, a.y() + 1.22, a.z), to = chest.subtract(eyes);
		double flat = Math.max(1e-6, Math.hypot(to.x, to.z));
		Vec3 at = chest.add(-to.z / flat * 0.27, 0, to.x / flat * 0.27);
		aimYaw = yawTo(eyes, at);
		aimPitch = pitchTo(eyes, at);
	}

	/**
	 * How far down a 1080-line frame the bottom of an agent's name tag is, seen through the player's eyes: the cut pushes
	 * in on the card from just under it, so the tag is out of frame instead of sliced by it. {@code height} is the tag's
	 * bottom above the agent's feet.
	 */
	private int tagBottom(Minecraft mc, OfficeBrain.Agent a, double height) {
		Vec3 to = d(a.x, a.y() + height, a.z).subtract(mc.player.getEyePosition());
		double yaw = Math.toRadians(mc.player.getYRot()), pitch = Math.toRadians(mc.player.getXRot());
		Vec3 ahead = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)), up = right.cross(ahead);
		return (int) Math.round(540 - 540 / Math.tan(Math.toRadians(mc.options.fov().get() / 2.0)) * to.dot(up) / to.dot(ahead));
	}

	private void lookAt(Minecraft mc, Vec3 point, double tolerance) {
		Vec3 eyes = mc.player.getEyePosition();
		hand.turnTo(yawTo(eyes, point), pitchTo(eyes, point), tolerance);
	}

	/**
	 * The mouse pointer a screen shows. It appears in the middle when the screen opens, as the game puts it there, and
	 * stays exactly where it is left: a mouse at rest does not drift.
	 */
	private void pointerOpen() {
		pointer = new DemoHand.Look(random.nextLong(), 96, 54);
		pointer.drift(0);
	}

	/** Moves the pointer to ({@code x}, {@code y}) of the GUI in one movement of the hand. */
	private void pointerTo(Screen screen, double x, double y) {
		if (pointer == null) return;
		double unit = 1080.0 / screen.height;
		pointer.turnTo(x * unit / 10, y * unit / 10, 0.4);
	}

	/** A left click where the pointer is. */
	private void click(Minecraft mc) {
		Screen screen = mc.gui.screen();
		if (screen == null || pointer == null) return;
		double unit = screen.height / 1080.0;
		var event = new net.minecraft.client.input.MouseButtonEvent(pointerX * unit, pointerY * unit, new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
		screen.mouseClicked(event, false);
		if (mc.gui.screen() == screen) screen.mouseReleased(event);
	}

	/**
	 * Through the player's eyes: the agent waves, a right-click opens its card, the request is read, the pointer goes to
	 * Approve and clicks, the card says what happens now and closes; the agent turns to go.
	 */
	private void card(Minecraft mc) {
		var a = waiting;
		Screen screen = mc.gui.screen();
		if (segment == null && phase == 0 && !ready(mc, atWhiteboard(), this::ask, "the agent that needs you never reached the whiteboard (status " + a.status + ", zone " + a.zone + ")")) return;
		if (t == 1) {
			clock(mc, 7600);
			if (stand == null) stand = standSpot(mc, a, 0);
			if (stand == null) { finish(mc, "FAIL no clear spot within reach of the waiting agent to click it from"); return; }
			Vec3 feet = d(stand[0], a.floor * T3Office.FLOOR_HEIGHT, stand[1]);
			aim(a, feet.add(0, 1.62, 0));
			// As the walk left it: on the agent already.
			player(mc, View.FIRST, 62, feet, aimYaw - 0.8f, aimPitch - 1.2f);
		}
		// It waves at whoever has come to help.
		if (t == 4) hold(mc, staged, AgentLife.Activity.WAVE);
		if (t == 11) mc.gui.toastManager().clear();
		if (t == 12) {
			begin("card");
			mark2 = DemoHand.wait(random, 0.62, 0.08);
		}
		if (segment == null) return;
		if (phase == 0) {
			if (rec() >= mark2 && onAgent(mc, a)) {
				phase(1);
				use(mc);
				swing(mc);
				mark("click");
			} else if (rec() > 4) finish(mc, "FAIL the crosshair is not on the waiting agent (" + mc.hitResult + ")");
		} else if (phase == 1) {
			if (screen instanceof T3AgentCardScreen c && c.approvalActiveForTest()) {
				mark("card");
				mark("tag", tagBottom(mc, a, 2.13));
				phase(2);
				// Hotbar and hearts would be cut in half by the push-in on the card: off while it is up, and for the line after it.
				hudHidden = true;
				// It explains itself while the request is read.
				hold(mc, staged, AgentLife.Activity.SPEAK);
				double read = DemoHand.wait(random, 2.85, 0.02);
				Button approve = button(screen, "Approve (Y)");
				// The pointer stays where the game put it while the card is read, then goes to Approve in one movement and clicks.
				pointerOpen();
				after(read - 0.5, () -> pointerTo(c, approve.getX() + approve.getWidth() * 0.6, approve.getY() + approve.getHeight() * 0.6));
				after(read, () -> {
					if (!(mc.gui.screen() instanceof T3AgentCardScreen)) { broken = "the card closed before the approval"; return; }
					click(mc);
					mark("approved");
					// It stays where it is, facing the player, while the card says what happens now.
					a.faceHold = 34;
					phase(3);
				});
			} else if (t - phaseAt > 50) finish(mc, "FAIL clicking the waiting agent did not open its card with the approval (screen " + screen + ")");
		} else if (phase == 3) {
			if (screen instanceof T3AgentCardScreen c && !c.approvalVisibleForTest() && status(staged) == T3State.Status.WORKING) {
				mark("working");
				phase(4);
				// Long enough to read what it says now, then back to the world; its tag says the same as it turns to go.
				after(DemoHand.wait(random, 1.5, 0.03), () -> {
					if (mc.gui.screen() != null) mc.gui.screen().onClose();
					release();
					mark("closed");
					// The card is gone before the line arrives: one message at a time. The eyes follow a moment after.
					overlay.caption(now() + 0.12, "No *alt-tab.");
					after(DemoHand.wait(random, 0.22, 0.15), () -> hand.follow(3));
					phase(5);
				});
			} else if (t - phaseAt > 80) finish(mc, "FAIL the approval did not go through (status " + status(staged) + ", screen " + screen + ")");
		} else if (phase == 5) {
			// Watch it walk off.
			Vec3 eyes = mc.player.getEyePosition(), head = head(a);
			hand.aim(yawTo(eyes, head), pitchTo(eyes, head), 0, 0);
			if (now() >= overlay.captionShown() + 1.2) next(mc);
		}
	}

	/** Behind the agent's shoulder as it sits back down and types, its screen and hands in view; the player comes up beside it and ducks in to look. */
	private void desk(Minecraft mc) {
		var a = waiting;
		boolean back = a.status == T3State.Status.WORKING && a.zone == OfficeBrain.Zone.WORK && a.goal != null && a.goal.seat();
		if (segment == null && phase == 0 && !ready(mc, back, () -> { if (a.status == T3State.Status.NEEDS_YOU) fixture("approve?id=" + staged); else if (a.status != T3State.Status.WORKING) fixture("work?id=" + staged); },
			"the approved agent is not heading back to a desk (status " + a.status + ", zone " + a.zone + ")")) return;
		double gx = a.goal.x() + 0.5, gz = a.goal.z() + 0.5, rad = Math.toRadians(a.goal.yaw()), fx = -Math.sin(rad), fz = Math.cos(rad), sx = fz, sz = -fx;
		double distance = Math.hypot(gx - a.x, gz - a.z), floor = a.floor * T3Office.FLOOR_HEIGHT;
		// What the player looks at: the screen, past the agent's shoulder (not the agent, whose name tag would grow and shrink as the eyes moved on and off it).
		Vec3 screenAt = d(gx + fx * 0.9 + sx * 0.45, floor + 1.1, gz + fz * 0.9 + sz * 0.45), beside = d(gx - fx * 1.2 + sx * 1.05, floor, gz - fz * 1.2 + sz * 1.05);
		if (t == 1) {
			clock(mc, 7650);
			// From along the wall behind the row, on the far side of the agent from the camera.
			Vec3 start = d(gx - fx * 1.25 + sx * 4.2, floor, gz - fz * 1.25 + sz * 4.2);
			player(mc, View.THIRD, 43, start, yawTo(start, beside), 5);
			// High enough, and steep enough, that the tags of the row across the bench are out of the top of the frame.
			Vec3 eye = d(gx - fx * 0.9 - sx * 1.5, floor + 2.65, gz - fz * 0.9 - sz * 1.5), at = d(gx + fx * 0.3 + sx * 0.6, floor + 0.9, gz + fz * 0.3 + sz * 0.6), push = at.subtract(eye).normalize();
			rig((s, p) -> six(eye.add(push.scale(0.06 * s)), at), 0, 0);
			mc.gui.toastManager().clear();
		}
		if (phase == 0) {
			// The player is already on its way over when the shot begins.
			if (t > 10 && walk == null && !flag && distance < 3.0) {
				walk = new Walk(false, 8, beside);
				walk.face = screenAt;
				flag = true;
			}
			if (walk != null) walk.tick(mc);
			if (t > 10 && (distance < 1.0 || a.settled())) {
				begin("desk");
				phase(1);
			} else if (t > 20 * 40) finish(mc, "FAIL the approved agent never came near its desk");
			return;
		}
		if (mark1 == 0 && a.settled() && distance < 0.8) {
			mark1 = now();
			mark("seated");
			// Hands on the keys, and the line.
			after(0.2, () -> hold(mc, staged, AgentLife.Activity.TYPE));
			overlay.caption(now() + 0.1, "Back to *work.");
		}
		if (walk != null) {
			walk.tick(mc);
			if (walk.arrived) {
				walk = null;
				keys.release();
				// Ducking in for a look at the screen, then up again.
				lookAt(mc, screenAt, 2);
				after(DemoHand.wait(random, 0.12, 0.2), () -> keys.sneak = true);
				after(DemoHand.wait(random, 0.95, 0.1), () -> { keys.sneak = false; hand.nudge(-9, -5); });
				mark("peek");
			}
		}
		if (mark1 > 0 && now() >= overlay.captionShown() + 1.4) next(mc);
		else if (rec() > 14) finish(mc, "FAIL the approved agent never sat back down at its desk");
	}

	/**
	 * A helper walks up to the agent that spawned it and is handed work: from low on the parent's side, so the helper
	 * comes toward the lens and answers into it.
	 */
	private void talk(Minecraft mc) {
		if (t == 1) {
			camera(mc, 50);
			clock(mc, 7700);
			// The game marks a line of speech with a note particle, which lands on the bubble: not in this shot.
			mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
			if (!delegated) fixture("delegate?parent=" + System.getProperty("t3craft.demoParent", "office-9") + "&driver=codex&title=" + System.getProperty("t3craft.demoHelperTitle", "Fix the merge conflicts").replace(" ", "%20"));
			delegated = true;
			firstSec = secondSec = -1;
			lastPhase = null;
			conversation = null;
		}
		if (phase == 0) {
			var live = mod.residents().talks().director().conversations().stream().filter(c -> c.phase == AgentTalk.Phase.APPROACH).findFirst().orElse(null);
			if (live == null) {
				if (t > 20 * 60) finish(mc, "FAIL no parent and helper started talking within a minute of the delegate call");
				return;
			}
			conversation = live;
			frameTalk();
			double[] mid = talkMid;
			double floor = talkFloor, base = talkAngle;
			int side = talkSide;
			rig((s, p) -> {
				// A slow swing toward the helper's face, timed from the parent's line.
				double since = firstSec < 0 ? 0 : s - firstSec, angle = base + side * Math.toRadians(40 - 2.5 * since), radius = 4.0 - 0.08 * since;
				return six(d(mid[0] + Math.cos(angle) * radius, floor + 1.0, mid[1] + Math.sin(angle) * radius), d(mid[0], floor + 2.0, mid[1]));
			}, 0.5, 0);
			phase(1);
			return;
		}
		var c = conversation;
		double[] meet = c.route.helperMeet();
		// Recording starts with the helper still on its way in.
		if (segment == null && Math.hypot(c.helper.x - meet[0], c.helper.z - meet[1]) < 6.2) { mc.gui.toastManager().clear(); begin("talk"); }
		if (segment == null) {
			if (t > 20 * 70) finish(mc, "FAIL the helper never came near the agent that spawned it");
			return;
		}
		if (!flag && Math.hypot(c.helper.x - meet[0], c.helper.z - meet[1]) < 4.9) {
			flag = true;
			mark("caption");
			overlay.caption(now(), "They hand work to *each *other.");
		}
		if (c.phase != lastPhase) {
			lastPhase = c.phase;
			mark(c.phase.name().toLowerCase(Locale.ROOT));
			// The line gives way to the speech bubble: one message at a time.
			if (c.phase == AgentTalk.Phase.FIRST) { firstSec = rec(); overlay.clearCaption(); }
			if (c.phase == AgentTalk.Phase.SECOND) secondSec = rec();
		}
		boolean over = c.phase == AgentTalk.Phase.DONE || !mod.residents().talks().director().conversations().contains(c);
		if (over && secondSec < 0) { finish(mc, "FAIL the conversation ended before the helper answered"); return; }
		if (rec() > 70) { finish(mc, "FAIL the conversation never reached the helper's answer"); return; }
		if (over || (secondSec > 0 && rec() - secondSec > 1.5)) next(mc);
	}

	/**
	 * Through the player's eyes: walk up to a finished agent on the sofa and right-click it; on its card, Open on laptop;
	 * the laptop shows every agent and this one's report; "ship it" is typed there and sent with Enter.
	 */
	private void ship(Minecraft mc) {
		var a = typist;
		Screen screen = mc.gui.screen();
		if (segment == null && phase == 0 && !ready(mc, a.status == T3State.Status.DONE && a.settled(), null, "the agent to type to is not resting (status " + a.status + ")")) return;
		if (t == 1) {
			clock(mc, 7750);
			double rad = Math.toRadians(a.yaw);
			double[] spot = standSpot(mc, a, Math.atan2(Math.cos(rad), -Math.sin(rad)));
			if (spot == null) { finish(mc, "FAIL no clear spot within reach of the finished agent at " + a.x + ", " + a.z + " to click it from"); return; }
			Vec3 feet = d(spot[0], a.floor * T3Office.FLOOR_HEIGHT, spot[1]), sofa = d(a.x, a.floor * T3Office.FLOOR_HEIGHT, a.z);
			aim(a, feet.add(0, 1.62, 0));
			// A couple of steps back from the mark: this one is walked up to.
			Vec3 from = feet.add(feet.subtract(sofa).normalize().scale(1.5));
			player(mc, View.FIRST, 62, from, aimYaw + 16, aimPitch - 9);
			stand = spot;
			mod.focus(typedTo);
		}
		if (t == 6) {
			walk = new Walk(false, aimPitch - 4, d(stand[0], 0, stand[1]));
			walk.face = d(a.x, a.y() + 1.3, a.z);
		}
		if (t == 9) mc.gui.toastManager().clear();
		if (t == 10) begin("ship");
		if (walk != null) {
			walk.tick(mc);
			if (walk.arrived) {
				walk = null;
				keys.release();
				hand.turnTo(aimYaw, aimPitch, 0.8);
			}
		}
		if (segment == null) return;
		if (phase == 0) {
			if (walk == null && !hand.moving() && onAgent(mc, a)) {
				phase(1);
				after(DemoHand.wait(random, 0.13, 0.2), () -> { use(mc); swing(mc); mark("click"); });
			} else if (rec() > 5) finish(mc, "FAIL the crosshair is not on the finished agent (" + mc.hitResult + ")");
		} else if (phase == 1) {
			if (screen instanceof T3AgentCardScreen c) {
				mark("card");
				mark("tag", tagBottom(mc, a, 2.01));
				phase(2);
				hudHidden = true;
				// It says what it did, hands moving; it will stay where it is until the player has answered.
				hold(mc, typedTo, AgentLife.Activity.SPEAK);
				a.faceHold = 20 * 30;
				Button open = button(screen, "Open on laptop");
				// A look at its report on the card, then the pointer goes to Open on laptop and clicks.
				pointerOpen();
				double look = DemoHand.wait(random, 1.05, 0.05);
				after(look - 0.45, () -> pointerTo(c, open.getX() + open.getWidth() * 0.57, open.getY() + open.getHeight() * 0.6));
				after(look, () -> {
					click(mc);
					if (!(mc.gui.screen() instanceof T3Screen)) { broken = "Open on laptop did not open the laptop (screen " + mc.gui.screen() + ")"; return; }
					mark("laptop");
					phase(3);
				});
			} else if (t - phaseAt > 50) finish(mc, "FAIL clicking the finished agent did not open its card (screen " + screen + ")");
		} else if (phase == 3 && screen instanceof T3Screen laptop && mark1 == 0) {
			mark1 = rec();
			// The list of every agent, this one's report; then the keys, each on the frame a hand would land it.
			double read = DemoHand.wait(random, 2.3, 0.03);
			double[] keyAt = DemoHand.keyTimes(TYPED, random);
			for (int i = 0; i < TYPED.length(); i++) {
				char key = TYPED.charAt(i);
				after(read + keyAt[i], () -> { if (mc.gui.screen() instanceof T3Screen panel) panel.charTyped(new net.minecraft.client.input.CharacterEvent(key)); });
			}
			double typed = read + keyAt[TYPED.length() - 1], enter = typed + DemoHand.wait(random, 0.5, 0.05);
			after(typed + 0.04, () -> {
				if (mc.gui.screen() instanceof T3Screen panel && panel.composerValueForTest().equals(TYPED)) mark("typed");
				else broken = "the message was not typed into the laptop";
			});
			float unit = 1080f / laptop.height;
			after(enter - 0.35, () -> overlay.keycap("Shift+Enter", now(), now() + 0.35, (laptop.width - 62) * unit, (laptop.height - 62) * unit, 4.6f));
			after(enter, () -> {
				if (!(mc.gui.screen() instanceof T3Screen panel)) { broken = "the laptop closed before the message was sent"; return; }
				// Shift keeps the laptop open after sending, so the message is seen arriving in the thread.
				panel.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 1));
				if (!(mc.gui.screen() instanceof T3Screen)) { broken = "the laptop closed on Shift+Enter"; return; }
				mark("sent");
				phase(4);
				// The message in the thread, the agent's dot turning blue in the list: read, then the laptop is shut.
				after(DemoHand.wait(random, 1.3, 0.03), () -> {
					if (mc.gui.screen() != null) mc.gui.screen().onClose();
					overlay.clearKeycap();
					mark("closed");
					mark2 = rec() + 0.12;
					phase(5);
				});
			});
		} else if (phase == 5 && rec() >= mark2) {
			if (status(typedTo) != T3State.Status.WORKING) { finish(mc, "FAIL the agent did not start working after the message (status " + status(typedTo) + ")"); return; }
			next(mc);
		}
	}

	/**
	 * The same moment from outside: a camera locked off to one side, the agent on its sofa throwing its arms up, the
	 * player jumping for it a beat later; then it gets up and goes back to work.
	 */
	private void cheer(Minecraft mc) {
		var a = typist;
		if (segment == null && phase == 0 && !ready(mc, a.status == T3State.Status.WORKING, () -> { a.faceHold = 20 * 30; fixture("work?id=" + typedTo); }, "the agent told to ship it is not working (status " + a.status + ")")) return;
		if (t == 1) {
			clock(mc, 7780);
			a.faceHold = Math.max(a.faceHold, 80);
			if (stand == null) { double rad = Math.toRadians(a.yaw); stand = standSpot(mc, a, Math.atan2(Math.cos(rad), -Math.sin(rad))); }
			if (stand == null) { finish(mc, "FAIL no clear spot in front of the agent told to ship it"); return; }
			double floor = a.floor * T3Office.FLOOR_HEIGHT, ux = stand[0] - a.x, uz = stand[1] - a.z, length = Math.hypot(ux, uz);
			Vec3 feet = d(stand[0], floor, stand[1]);
			player(mc, View.THIRD, 46, feet, yawTo(feet, d(a.x, floor, a.z)), 8);
			hand.drift(0.3);
			// Side on, a little toward the player's end so the agent's face is to the lens; the agent on the left third, the player on the right.
			double mx = (a.x + stand[0]) / 2, mz = (a.z + stand[1]) / 2, px = -uz / length, pz = ux / length;
			Vec3 one = d(mx + px * 4.3 + ux / length * 0.9, floor + 1.45, mz + pz * 4.3 + uz / length * 0.9), other = d(mx - px * 4.3 + ux / length * 0.9, floor + 1.45, mz - pz * 4.3 + uz / length * 0.9);
			Vec3 at = d(mx, floor + 1.65, mz), agent = d(a.x, floor, a.z);
			// From whichever side has the agent on the left of the frame.
			Vec3 view = at.subtract(one), toAgent = agent.subtract(one);
			Vec3 eye = view.x * toAgent.z - view.z * toAgent.x < 0 ? one : other;
			rig((s, p) -> six(eye, at), 0, 0);
			mc.gui.toastManager().clear();
		}
		if (t == 12) {
			begin("cheer");
			double arms = DemoHand.wait(random, 0.22, 0.1), jump = arms + DemoHand.wait(random, 0.38, 0.08);
			after(arms, () -> act(mc, typedTo, AgentLife.Activity.STRETCH, 1.0));
			after(jump, () -> keys.jump = 2);
			after(jump + DemoHand.wait(random, 0.62, 0.05), () -> keys.jump = 2);
			// Then it gets up.
			after(1.2, () -> a.faceHold = 0);
		}
		if (segment != null && rec() >= 1.5) next(mc);
	}

	/** Pulling back from the glass front at dusk with the lamps on, under the title. Its last frame is the poster. */
	private void title(Minecraft mc) {
		int[] e = OfficeDesign.EXTENT;
		double midX = (e[0] + e[1]) / 2.0 + 0.5;
		if (t == 1) {
			camera(mc, 58);
			clock(mc, 13100);
			// From close enough to see the agents at their lit desks, out to the whole building above the water, where the title has room under it.
			Vec3 near = d(midX - 4, 3.0, e[3] + 9), far = d(midX + 3, 10.0, e[3] + 44), close = d(midX, 2.2, e[3]), wide = d(midX, -0.9, e[3]);
			// Still slowing at the last frame, never stopped.
			rig((s, p) -> {
				double k = 1 - Math.exp(-(s + 0.3) / 1.3);
				return six(mix(near, far, k), mix(close, wide, k));
			}, 0, 0);
		}
		// Lamps follow the night on the office's own timer; give it time to finish switching every floor.
		if (t == 165) { clock(mc, 13100); mc.gui.toastManager().clear(); }
		if (t == 170) {
			begin("end");
			overlay.title(now() + 0.15, "T3 Craft", "A free, open-source mod for Minecraft Java.", "Claude Code, Codex and more, through T3 Code.", "github.com/alexandros-lekkas/t3craft");
			mark("caption");
		}
		if (segment != null && rec() >= 4.8) next(mc);
	}

	// ---- Finding places in the office ----

	/** The opening looks across the bench at the first row from the front: its middle agent, the row's direction, and the way it faces. */
	private boolean frameHook(java.util.Collection<OfficeBrain.Agent> agents) {
		var lead = agents.stream().filter(a -> a.threadId.equals("office-0")).findFirst().orElse(null);
		if (lead == null) return false;
		double rad = Math.toRadians(lead.yaw), fx = -Math.sin(rad), fz = Math.cos(rad), rx = fz, rz = -fx;
		// Agents in one row share a heading and a position along it.
		List<OfficeBrain.Agent> row = agents.stream().filter(a -> a.floor == 0 && a.zone == OfficeBrain.Zone.WORK && a.status == T3State.Status.WORKING && a.settled()
			&& Math.abs(Mth.wrapDegrees(a.yaw - lead.yaw)) < 1 && Math.abs((a.x - lead.x) * fx + (a.z - lead.z) * fz) < 0.5)
			.sorted(java.util.Comparator.comparingDouble(a -> a.x * rx + a.z * rz)).toList();
		if (row.size() < 3) return false;
		var middle = row.get(1);
		hookAlong = dir(rx, rz);
		hookOut = dir(fx, fz);
		hookRow = d(middle.x, 1.3, middle.z);
		hookStretch = middle.threadId;
		T3CraftClient.LOGGER.info("DEMO opening on a row of {} agents round {}", row.size(), middle.threadId);
		return true;
	}

	/** Level ground a dozen blocks out from the door, with room for a tree two blocks further on. */
	private Lawn surveyLawn(Minecraft mc, int[] door, int[] e) {
		var level = mc.level;
		for (int back : new int[] {17, 19, 15, 13, 11}) for (int dx : new int[] {0, -3, 3, -6, 6, -9, 9, -12, 12}) {
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
			return new Lawn(new Vec3(feet.getX() + 0.5, ground, feet.getZ() + 0.5), tree.atY(ground));
		}
		return null;
	}

	/** The camera for the conversation stands on the parent's side, off the line between them, on whichever side is inside the room and clear of other agents. */
	private void frameTalk() {
		var c = conversation;
		double[] a = c.route.parentMeet(), b = c.route.helperMeet();
		double mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2, axis = Math.atan2(a[1] - b[1], a[0] - b[0]);
		double best = Double.MAX_VALUE;
		for (int side : new int[] {1, -1}) {
			double angle = axis + side * Math.toRadians(40), ex = mx + Math.cos(angle) * 4.0, ez = mz + Math.sin(angle) * 4.0, cost = 0;
			if (ex < 1 || ez < 1 || ex >= T3Office.WIDTH - 1 || ez >= T3Office.LENGTH - 1) cost += 100;
			// It is low, so it stands on open floor with open floor between it and the pair.
			boolean[][] blocked = T3Office.blocked();
			for (double k = 0; k <= 0.75; k += 0.25) if (blockedAt(blocked, ex + (mx - ex) * k, ez + (mz - ez) * k)) cost += 30;
			// Nobody else between the lens and the pair.
			for (var other : mod.residents().brainForTest().agents().values()) {
				if (other == c.parent || other.threadId.equals(c.helper.threadId) || other.floor != c.parent.floor) continue;
				double along = ((other.x - ex) * (mx - ex) + (other.z - ez) * (mz - ez)) / 16;
				if (along > -0.1 && along < 1.3 && Math.hypot(other.x - (ex + (mx - ex) * along), other.z - (ez + (mz - ez) * along)) < 1.4) cost += 4;
			}
			if (cost < best) { best = cost; talkSide = side; }
		}
		talkAngle = axis;
		talkMid = new double[] {mx, mz};
		talkFloor = c.parent.floor * T3Office.FLOOR_HEIGHT;
		T3CraftClient.LOGGER.info("DEMO talk at {}, {} along {} degrees, camera side {}", mx, mz, Math.round(Math.toDegrees(axis)), talkSide);
	}

	/**
	 * Where the player stands to click an agent: about two and a half blocks away, as near {@code preferred} (a design
	 * angle from the agent) as the room allows. A spot counts only if the player fits there and nothing in the world
	 * stands between its eyes and the agent's head, waist or feet, so it follows the furniture rather than a fixed layout.
	 */
	private double[] standSpot(Minecraft mc, OfficeBrain.Agent a, double preferred) {
		boolean[][] blocked = T3Office.blocked();
		double floor = a.floor * T3Office.FLOOR_HEIGHT;
		T3Office.Spot seat = T3Office.WORK[a.desk];
		List<double[]> home = a.zone == OfficeBrain.Zone.NEEDS_YOU ? T3Office.path((int) Math.floor(a.x), (int) Math.floor(a.z), seat.x(), seat.z()) : List.of();
		for (int offset : new int[] {0, 12, -12, 24, -24, 36, -36, 48, -48, 60, -60, 75, -75, 90, -90, 110, -110, 130, -130, 150, -150, 180}) {
			for (double reach : new double[] {2.6, 2.3, 2.85}) {
				double angle = preferred + Math.toRadians(offset), cx = a.x + Math.cos(angle) * reach, cz = a.z + Math.sin(angle) * reach;
				boolean clear = true;
				// Not in the agent's way back to its desk: it would walk straight through the player.
				for (double[] step : home) clear &= Math.hypot(step[0] - cx, step[1] - cz) > 1.35;
				for (double dx = -0.35; dx <= 0.35 && clear; dx += 0.35) for (double dz = -0.35; dz <= 0.35 && clear; dz += 0.35) {
					clear = !blockedAt(blocked, cx + dx, cz + dz);
					BlockPos feet = BlockPos.containing(d(cx + dx, floor + 0.1, cz + dz));
					// A rug underfoot is fine.
					clear &= mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).max(net.minecraft.core.Direction.Axis.Y) < 0.2 && mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty();
					clear &= !mc.level.getBlockState(feet.below()).getCollisionShape(mc.level, feet.below()).isEmpty();
				}
				if (!clear) continue;
				Vec3 eyes = d(cx, floor + 1.62, cz), head = d(a.x, a.y() + 1.4, a.z);
				// Head in sight, and nothing (a sofa back, a counter) across the body: the lower lines stop short of the agent's own seat.
				Vec3 near = mix(head, eyes, 0.8 / Math.max(0.9, flat(head, eyes)));
				for (Vec3 to : new Vec3[] {head, head.add(0, -0.2, 0), new Vec3(near.x, head.y - 0.5, near.z), new Vec3(near.x, head.y - 0.85, near.z)}) {
					clear &= mc.level.clip(new net.minecraft.world.level.ClipContext(eyes, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
						net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
				}
				if (clear) return new double[] {cx, cz};
			}
		}
		return null;
	}

	private static boolean blockedAt(boolean[][] blocked, double x, double z) {
		int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
		return xi < 0 || zi < 0 || xi >= T3Office.WIDTH || zi >= T3Office.LENGTH || blocked[xi][zi];
	}

	// ---- Helpers ----

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
		if (step == Step.DONE) return;
		end();
		step = Step.DONE;
		try {
			List<String> others = keep("look.csv", ",", true);
			Files.writeString(out.resolve("look.csv"), looks + (others.isEmpty() ? "" : String.join("\n", others) + "\n"), StandardCharsets.UTF_8);
			others = keep("camera.csv", ",", true);
			Files.writeString(out.resolve("camera.csv"), cameras + (others.isEmpty() ? "" : String.join("\n", others) + "\n"), StandardCharsets.UTF_8);
		} catch (IOException e) {
			T3CraftClient.LOGGER.warn("DEMO could not write look.csv", e);
		}
		T3CraftClient.LOGGER.info("DEMO RESULT {}", result);
		mc.execute(mc::stop);
	}

	/**
	 * An empty screen kept open while the player mines: with a screen up the game does not read its own attack key
	 * (which, unpressed, would cancel the mining every tick). It draws nothing; the HUD and the crosshair stay.
	 */
	private static final class Quiet extends Screen {
		Quiet() { super(Component.literal("T3 Craft")); }
		@Override public boolean isPauseScreen() { return false; }
		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}
	}
}
