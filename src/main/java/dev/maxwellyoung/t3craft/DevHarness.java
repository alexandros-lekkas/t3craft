package dev.maxwellyoung.t3craft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only, enabled with {@code -Dt3craft.harness=<directory>} (what {@code scripts/mc run} passes): lets a script
 * or a coding agent look at a running, hidden dev client without touching the keyboard or the screen.
 * <ul>
 * <li>{@code <directory>/shot-request} containing a name: saves the game's framebuffer to
 * {@code screenshots/<name>.png} and writes the file name to {@code <directory>/shot-done}.</li>
 * <li>{@code <directory>/quit-request}: saves and quits, as the Quit Game button would.</li>
 * </ul>
 * Never active in normal play; everything else (commands, status) goes through the Minecraft MCP server.
 */
final class DevHarness {
	private final Path dir;
	private int ticks;

	private DevHarness(Path dir) { this.dir = dir; }

	static void register() {
		String dir = System.getProperty("t3craft.harness");
		if (dir == null || dir.isBlank()) return;
		DevHarness harness = new DevHarness(Path.of(dir));
		ClientTickEvents.END_CLIENT_TICK.register(harness::tick);
		T3Log.LOGGER.info("Dev harness watching {}", harness.dir);
	}

	private void tick(Minecraft minecraft) {
		if (++ticks % 4 != 0) return;
		try {
			Path quit = dir.resolve("quit-request");
			if (Files.deleteIfExists(quit)) { T3Log.LOGGER.info("Dev harness: quit requested"); minecraft.stop(); return; }
			Path request = dir.resolve("shot-request");
			if (!Files.exists(request)) return;
			String name = Files.readString(request).trim().replaceAll("[^A-Za-z0-9._-]", "-");
			Files.delete(request);
			String file = (name.isEmpty() ? "shot" : name) + ".png";
			Screenshot.grab(minecraft.gameDirectory, file, minecraft.gameRenderer.mainRenderTarget(), 1, message -> {
				T3Log.LOGGER.info("Dev harness shot {} {}", file, message.getString());
				try { Files.writeString(dir.resolve("shot-done"), file); } catch (IOException e) { T3Log.LOGGER.warn("Dev harness could not report the shot", e); }
			});
		} catch (IOException e) {
			T3Log.LOGGER.warn("Dev harness request failed", e);
		}
	}
}
