package dev.maxwellyoung.t3craft;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * How an office agent moves on screen, shared by every office (the client's own, the shared server office, and agents
 * walking over to one another). Each tick: the old position is where it stood, the new one where the brain put it, so
 * the renderer interpolates between them; the body turns toward the heading over a few ticks instead of snapping; and
 * the legs and arms swing with the distance covered, by vanilla's own rule.
 *
 * <p>Why the agents slid: on the client {@code LivingEntity.aiStep} calls {@code calculateEntityAnimation}, which
 * measures {@code getX() - xo}. The level tick sets {@code xo = x} just before that, and the office moves its agents
 * only after the level tick, so vanilla always measured zero and the walk never started. Agents driven here skip that
 * vanilla update ({@code mixin.AgentWalkMixin}) and get the same update from the distance they really moved.
 */
public final class AgentWalk {
	/** Blocks per tick: an unhurried walk, about a villager's pace. */
	static final double SPEED = 0.12;
	/** Share of the remaining turn taken each tick: a right-angle corner takes about half a second. */
	static final float TURN = 0.3f;
	/** Further than this in one tick is a placement (a floor change, a rebuilt office), not a step: no slide, no swing. */
	private static final double TELEPORT = 1.0;

	private static final Set<LivingEntity> DRIVEN = Collections.newSetFromMap(new WeakHashMap<>());

	private AgentWalk() {}

	/** Whether this entity's walk is animated here, so vanilla's own (always zero) per-tick update is skipped. */
	public static boolean driven(LivingEntity entity) {
		return DRIVEN.contains(entity);
	}

	/**
	 * One tick: move to {@code to}, turn the body toward {@code yaw} (world degrees) and swing the limbs with the
	 * horizontal distance covered. {@code seated}: it just sat down (desk chair, sofa), so the swing stops at once
	 * rather than finishing a stride in the chair.
	 */
	static void step(LivingEntity avatar, Vec3 to, float yaw, boolean seated) {
		DRIVEN.add(avatar);
		double ox = avatar.getX(), oy = avatar.getY(), oz = avatar.getZ();
		double dx = to.x - ox, dz = to.z - oz;
		double moved = Math.sqrt(dx * dx + dz * dz);
		boolean placed = moved > TELEPORT || Math.abs(to.y - oy) > TELEPORT;
		avatar.setPos(to);
		// Old then new, so the frame between ticks is drawn part way along the step (or right there, when placed).
		avatar.xo = placed ? to.x : ox;
		avatar.yo = placed ? to.y : oy;
		avatar.zo = placed ? to.z : oz;
		float body = placed ? yaw : turn(avatar.getYRot(), yaw);
		avatar.setYRot(body);
		avatar.setYBodyRot(body);
		animate(avatar.walkAnimation, placed ? 0 : moved, seated);
	}

	/** The body's heading after one tick of turning from {@code current} toward {@code target}. */
	static float turn(float current, float target) {
		float next = Mth.rotLerp(TURN, current, target);
		return Math.abs(Mth.wrapDegrees(target - next)) < 0.5f ? target : next;
	}

	/**
	 * Vanilla's walk rule ({@code LivingEntity.updateWalkAnimation} in 26.3): the limb swing eases 40% of the way
	 * toward four times the horizontal distance moved this tick, capped at 1, and the stride advances by the swing.
	 * Standing still, it eases back to rest; sitting down, it stops.
	 */
	static void animate(WalkAnimationState walk, double moved, boolean seated) {
		if (seated) {
			walk.stop();
			return;
		}
		walk.update(Math.min((float) moved * 4.0f, 1.0f), 0.4f, 1.0f);
	}
}
