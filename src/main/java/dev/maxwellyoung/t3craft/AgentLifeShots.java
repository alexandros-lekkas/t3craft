package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Dev-only: photographs the office's characters for {@link WorldSelfTest} ({@code -Pworldlife}). Animation is about
 * motion, so each scene is a short run of frames a few ticks apart rather than one still: workers typing, a worker
 * glancing up as you walk over, the name tags from near and far, the waiting agent, the lounge, and the idles.
 * Frames land in {@code run/screenshots/life-<scene>-<n>.png}.
 */
final class AgentLifeShots {
	/**
	 * One scene: stand at a design-relative spot with a design-relative look, wait, then take {@code frames} shots
	 * {@code every} ticks apart. {@code idle} holds every agent in that idle for the scene (null: whatever it is doing).
	 */
	private record Scene(String name, double x, double y, double z, float yaw, float pitch, int settle, int frames, int every, String idle) {}

	private static final List<Scene> SCENES = List.of(
		// Back from both desk rows: nobody is close enough to look up, so they work.
		new Scene("typing", 6.5, 0.4, 9.9, 180, 8, 60, 10, 4, null),
		// The same rows from across the room: status dots only.
		new Scene("tags-far", 6.5, 0.6, 17.5, 180, 6, 40, 1, 1, null),
		// Walk up behind the near row: they look round, hands off the keys, then go back to work.
		new Scene("glance", 6.5, 0, 7.6, 180, 8, 3, 16, 8, null),
		// The agent waiting at the whiteboard, from across the floor and from close by.
		new Scene("needs-you-far", 13.5, 0.3, 16.5, 90, 4, 40, 6, 5, null),
		new Scene("needs-you", 6.5, 0, 16.5, 90, 0, 30, 4, 6, null),
		// Finished agents on the sofas.
		new Scene("lounge", 0, 0.3, 5.2, 0, 0, 40, 4, 10, "@LOUNGE"),
		new Scene("lounge-near", 0.8, 0.2, 2.6, 0, 0, 30, 6, 6, "@LOUNGE"),
		// The desk idles, each held for the photograph, from beside the first row.
		new Scene("idle-think", 6.5, 0.4, 9.9, 180, 8, 40, 3, 8, "THINK"),
		new Scene("idle-stretch", 6.5, 0.4, 9.9, 180, 8, 30, 3, 8, "STRETCH"),
		new Scene("idle-sip", 6.5, 0.4, 9.9, 180, 8, 30, 3, 8, "SIP"),
		new Scene("idle-close", 1.2, 0.2, 0.9, -62, 6, 30, 2, 10, "THINK"),
		new Scene("sip-close", 1.2, 0.2, 0.9, -62, 6, 30, 2, 10, "SIP"));

	private final T3CraftClient mod;
	private int scene, tick, frame;

	AgentLifeShots(T3CraftClient mod) {
		this.mod = mod;
	}

	/** One client tick; false once every scene is photographed. */
	boolean tick(Minecraft minecraft, OfficeFrame office) {
		if (scene >= SCENES.size()) return false;
		Scene s = SCENES.get(scene);
		if (tick == 0) {
			// Name tags show with the HUD.
			if (minecraft.gui.hud.isHidden()) minecraft.gui.hud.toggle();
			minecraft.gui.hud.getChat().clearMessages(false);
			minecraft.player.getAbilities().flying = true;
			hold(s.idle == null || s.idle.startsWith("@") ? null : s.idle);
		}
		// Re-seat the camera now and then: creative flight drifts a little.
		if (tick == 0 || tick % 40 == 0 && tick < s.settle) {
			if (s.idle != null && s.idle.startsWith("@")) {
				// "@ZONE": stand in front of the first agent settled in that zone (x blocks to its side, z blocks ahead), looking at it.
				var zone = OfficeBrain.Zone.valueOf(s.idle.substring(1));
				var agent = mod.residents().brainForTest().agents().values().stream().filter(a -> a.zone == zone && a.settled()).findFirst().orElse(null);
				if (agent == null) { T3CraftClient.LOGGER.info("WORLDTEST life: nobody settled in {}", zone); scene++; tick = 0; return true; }
				double yaw = Math.toRadians(agent.goal.yaw()), fx = -Math.sin(yaw), fz = Math.cos(yaw), floor = agent.floor * T3Office.FLOOR_HEIGHT;
				Vec3 at = office.point(agent.x + fx * s.z + fz * s.x, floor + s.y, agent.z + fz * s.z - fx * s.x), look = office.point(agent.x, floor + 1.0, agent.z);
				minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f", at.x, at.y, at.z, look.x, look.y, look.z));
			} else {
				Vec3 at = office.point(s.x, s.y, s.z);
				minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", at.x, at.y, at.z, office.yaw(s.yaw), s.pitch));
			}
		}
		tick++;
		if (tick < s.settle) return true;
		int since = tick - s.settle;
		if (since % s.every == 0 && frame < s.frames) {
			minecraft.gui.hud.getChat().clearMessages(false);
			String file = String.format(Locale.ROOT, "life-%s-%02d.png", s.name, frame++);
			Screenshot.grab(minecraft.gameDirectory, file, minecraft.gameRenderer.mainRenderTarget(), 1,
				message -> T3CraftClient.LOGGER.info("WORLDTEST life shot {}", message.getString()));
		}
		if (frame >= s.frames) {
			hold(null);
			scene++;
			tick = 0;
			frame = 0;
		}
		return true;
	}

	/** Holds every agent in one idle for a photograph; null lets them choose again. */
	private void hold(String idle) {
		AgentLife.Activity activity = idle == null ? null : AgentLife.Activity.valueOf(idle);
		for (String id : mod.residents().brainForTest().agents().keySet()) {
			var mannequin = mod.residents().mannequinFor(id);
			if (mannequin != null) AgentLife.hold(mannequin, activity);
		}
	}
}
