package dev.maxwellyoung.t3craft;

import net.minecraft.world.entity.Entity;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What an office agent's arms, head and back are doing: typing in bursts, leaning back to think, stretching, sipping,
 * waving for attention, lounging, talking, listening. The office picks an {@link Activity} each tick; the model mixin
 * asks for the pose each frame. Poses ease into one another over {@link #BLEND} ticks, and every agent runs on its own
 * phase (seeded by its thread), so two neighbours never move in step. No client classes: the pose is plain angles.
 */
public final class AgentLife {
	public enum Activity {
		/** Vanilla's own pose: walking, standing, or seated with hands in the lap (looking at you). */
		FREE, TYPE, THINK, STRETCH, SIP, WAVE, LOUNGE, SPEAK, LISTEN
	}

	/** Pose channels, radians: right arm x/y/z, left arm x/y/z, extra head pitch and roll, and how far the back leans (seated). */
	public static final int RAX = 0, RAY = 1, RAZ = 2, LAX = 3, LAY = 4, LAZ = 5, HEAD_X = 6, HEAD_Z = 7, LEAN = 8, CHANNELS = 9;
	/** Ticks to ease from one pose into the next. */
	static final float BLEND = 9f;

	private static final class State {
		Activity activity = Activity.FREE, held;
		final float[] from = new float[CHANNELS], last = new float[CHANNELS];
		float since = -1000;
		int seed;
		boolean drink;
	}

	/** Client game thread only (the office tick and the renderer). */
	private static final Map<Entity, State> STATES = new WeakHashMap<>();
	private static final float[] TARGET = new float[CHANNELS];

	private AgentLife() {}

	/** What this agent is doing from now on; a change eases in from wherever its arms are. */
	static void set(Entity entity, Activity activity, int seed, boolean drink) {
		State s = STATES.computeIfAbsent(entity, e -> new State());
		s.seed = seed;
		s.drink = drink;
		if (s.activity == activity) return;
		s.activity = activity;
		if (s.held == null) restart(s, entity);
	}

	/** Dev: holds an agent in one activity (for a photograph) until released with null. */
	static void hold(Entity entity, Activity activity) {
		State s = STATES.get(entity);
		if (s == null || s.held == activity) return;
		s.held = activity;
		restart(s, entity);
	}

	private static void restart(State s, Entity entity) {
		System.arraycopy(s.last, 0, s.from, 0, CHANNELS);
		s.since = entity.tickCount;
	}

	static boolean known(Entity entity) {
		return STATES.containsKey(entity);
	}

	/** What the agent is doing right now (a held activity wins); FREE for an entity the office does not drive. */
	public static Activity activity(Entity entity) {
		State s = STATES.get(entity);
		return s == null ? Activity.FREE : s.held != null ? s.held : s.activity;
	}

	/**
	 * This frame's pose. {@code vanilla} holds the model's own values for the six arm channels (the rest 0); the
	 * result is those eased toward the activity's pose. Null for an entity the office does not drive.
	 */
	public static float[] pose(Entity entity, float age, boolean seated, float[] vanilla) {
		State s = STATES.get(entity);
		if (s == null) return null;
		Activity activity = s.held != null ? s.held : s.activity;
		target(activity, age + s.seed % 613, s.seed, seated, s.drink, TARGET);
		float w = smooth((age - s.since) / BLEND);
		for (int i = 0; i < CHANNELS; i++) {
			float to = Float.isNaN(TARGET[i]) ? vanilla[i] : TARGET[i];
			s.last[i] = s.from[i] + (to - s.from[i]) * w;
		}
		return s.last;
	}

	// ---- Schedules ----

	/**
	 * What a seated worker does at its desk on this tick: mostly typing, with a short break (thinking, a sip, a
	 * stretch) at the start of some stretches of work, never two breaks in a row.
	 */
	static Activity desk(int seed, long tick) {
		int length = 150 + seed % 50;
		long t = tick + seed % 997;
		long slot = t / length;
		Activity now = pick(seed, slot);
		if (now != Activity.TYPE && pick(seed, slot - 1) != Activity.TYPE) return Activity.TYPE;
		int into = (int) (t % length);
		int lasts = switch (now) { case THINK -> 90; case SIP -> 46; case STRETCH -> 56; default -> length; };
		return into < lasts ? now : Activity.TYPE;
	}

	private static Activity pick(int seed, long slot) {
		int r = hash(seed, slot) % 100;
		return r < 52 ? Activity.TYPE : r < 70 ? Activity.THINK : r < 86 ? Activity.SIP : Activity.STRETCH;
	}

	static int hash(int seed, long n) {
		long h = seed * 0x9E3779B97F4A7C15L + n * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return (int) (h & 0x7fffffff);
	}

	/** 0..1: how hard the hands are typing at {@code t}; bursts of keystrokes with a pause to read between them. */
	static float burst(int seed, float t) {
		float period = 46 + seed % 17;
		float into = t % period;
		float on = period * (0.66f + hash(seed, (long) (t / period)) % 24 / 100f);
		return smooth(into / 3f) * smooth((on - into) / 4f);
	}

	// ---- Poses ----

	/** The pose for an activity at {@code t} ticks; NaN leaves an arm channel to vanilla. */
	static void target(Activity activity, float t, int seed, boolean seated, boolean drink, float[] p) {
		Arrays.fill(p, Float.NaN);
		p[HEAD_X] = p[HEAD_Z] = p[LEAN] = 0;
		switch (activity) {
			case TYPE -> {
				float burst = burst(seed, t), tap = 0.07f * burst, drift = 0.06f * sin(t * 0.13f + seed);
				p[RAX] = -1.15f + tap * sin(t * 1.3f);
				p[LAX] = -1.15f - tap * sin(t * 1.3f + 0.6f);
				p[RAY] = -0.14f + drift;
				p[LAY] = 0.14f + drift * 0.6f;
				p[RAZ] = p[LAZ] = 0;
				// Eyes on the keys while typing, up at the screen in the pauses.
				p[HEAD_X] = 0.05f + 0.09f * burst + 0.02f * sin(t * 0.07f);
				p[HEAD_Z] = 0.03f * sin(t * 0.045f + seed);
				p[LEAN] = -0.05f;
			}
			case THINK -> {
				// Leaning back, one hand on the head, looking at the ceiling.
				p[RAX] = -2.8f;
				p[RAY] = 0;
				p[RAZ] = 0.42f + 0.07f * sin(t * 0.9f);
				p[HEAD_X] = -0.3f + 0.03f * sin(t * 0.06f);
				p[HEAD_Z] = 0.1f;
				p[LEAN] = 0.2f;
			}
			case STRETCH -> {
				float sway = 0.08f * sin(t * 0.22f);
				p[RAX] = p[LAX] = -2.95f;
				p[RAY] = p[LAY] = 0;
				p[RAZ] = -0.42f + sway;
				p[LAZ] = 0.42f + sway;
				p[HEAD_X] = -0.32f;
				p[LEAN] = 0.22f;
			}
			case SIP -> sip(p, 1);
			case WAVE -> {
				// A hand up for attention, with a wave every few seconds.
				float into = t % 110f, wave = smooth(into / 6f) * smooth((44 - into) / 6f);
				p[RAX] = -2.9f;
				p[RAY] = 0;
				p[RAZ] = -0.22f + wave * 0.3f * sin(t * 0.62f);
				p[HEAD_Z] = 0.05f * wave * sin(t * 0.62f);
			}
			case LOUNGE -> {
				p[RAX] = p[LAX] = -0.42f;
				p[RAY] = p[LAY] = 0;
				p[RAZ] = 0.3f;
				p[LAZ] = -0.3f;
				p[HEAD_X] = -0.08f;
				p[HEAD_Z] = 0.07f * sin(t * 0.03f + seed);
				p[LEAN] = 0.24f;
				if (drink) {
					float period = 240 + seed % 120, into = t % period;
					float up = smooth(into / 8f) * smooth((44 - into) / 8f);
					if (up > 0) {
						float[] sip = new float[CHANNELS];
						Arrays.fill(sip, Float.NaN);
						sip(sip, up);
						p[RAX] += (sip[RAX] - p[RAX]) * up;
						p[RAY] += (sip[RAY] - p[RAY]) * up;
						p[RAZ] += (sip[RAZ] - p[RAZ]) * up;
						p[HEAD_X] += (sip[HEAD_X] - p[HEAD_X]) * up;
					}
				}
			}
			case SPEAK -> {
				// Talking with the hands.
				p[RAX] = -0.8f + 0.3f * sin(t * 0.42f);
				p[RAY] = -0.25f + 0.15f * sin(t * 0.31f);
				p[RAZ] = 0.1f;
				p[LAX] = -0.3f + 0.14f * sin(t * 0.36f + 1);
				p[LAY] = 0.1f;
				p[LAZ] = -0.1f;
				p[HEAD_X] = 0.05f * sin(t * 0.5f);
			}
			case LISTEN -> {
				// A couple of nods now and then.
				float into = t % 64f;
				p[HEAD_X] = into < 23 ? 0.16f * Math.max(0, sin(into * 0.55f)) : 0;
			}
			case FREE -> { }
		}
		if (!seated) p[LEAN] = 0;
	}

	/** Cup to the mouth, head tipped back. */
	private static void sip(float[] p, float amount) {
		p[RAX] = -1.95f;
		p[RAY] = -0.5f;
		p[RAZ] = 0;
		p[HEAD_X] = -0.2f * amount;
	}

	static float smooth(float x) {
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}

	private static float sin(float x) {
		return (float) Math.sin(x);
	}
}
