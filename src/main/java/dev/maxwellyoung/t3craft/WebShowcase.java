package dev.maxwellyoung.t3craft;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Dev-only, read-only in-game capture of the real T3 UI ({@code ./gradlew runClient -PwebShowcase=<threadId>}):
 * opens the laptop, types into T3's sidebar search (and checks the page really received it), answers a simulated
 * ping with the panel key, opens the agent card the way a right-click does (a physical agent click when this
 * client is paired and that agent stands in the office), screenshots each, then quits. It never sends anything to a
 * thread.
 *
 * <ul>
 *   <li>{@code -PwebPing=<threadId>}: a second thread for the ping-jump capture.</li>
 *   <li>{@code -PwebPair=<link>}: pairs this dev client first (a T3 {@code /pair#token=} link), so the office has agents.</li>
 * </ul>
 */
final class WebShowcase {
	private final T3CraftClient mod;
	private final String threadId;
	private final String pingThreadId;
	private final String pairLink;
	private int ticks = -1;
	private int stage;
	private int attempts;
	private int offset;
	private boolean paired;
	private boolean clickedFocus = true;
	private boolean pairRequested;

	private WebShowcase(T3CraftClient mod, String threadId, String pingThreadId, String pairLink) {
		this.mod = mod;
		this.threadId = threadId;
		this.pingThreadId = pingThreadId;
		this.pairLink = pairLink;
	}

	static WebShowcase fromSystemProperty(T3CraftClient mod) {
		String thread = System.getProperty("t3craft.webShowcase");
		if (thread == null || thread.isBlank()) return null;
		return new WebShowcase(mod, thread, blankToNull(System.getProperty("t3craft.webPing")), blankToNull(System.getProperty("t3craft.webPair")));
	}

	private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

	private boolean worldRequested;
	private int menuTicks;

	void tick(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null) { enterWorld(minecraft); return; }
		ticks++;
		switch (stage) {
			case 0 -> pair(minecraft);
			case 1 -> { if (ticks >= 100) { mod.focus(threadId); mod.openLaptop(); next(); } }
			case 2 -> { if (ready(minecraft) && ticks >= 120) { shot(minecraft, "laptop"); next(); } }
			case 3 -> { if (ticks >= 20 && minecraft.gui.screen() instanceof T3WebScreen screen) { typeSearch(screen); next(); } }
			case 4 -> verifyTyping(minecraft);
			case 5 -> { if (ticks >= 20) { minecraft.gui.setScreen(null); next(); } }
			case 6 -> { if (ticks >= 40) { if (pingThreadId == null) stage = 10; else { mod.pingForShowcase(pingThreadId); next(); } } }
			case 7 -> { if (ticks >= 15) { shot(minecraft, "ping-toast"); KeyMapping.click(mod.openKey().getDefaultKey()); next(); } }
			case 8 -> { if (ready(minecraft) && ticks >= 120) { verifyRoute(minecraft, pingThreadId, "ping-jump"); next(); } }
			case 9 -> { if (ticks >= 20) { minecraft.gui.setScreen(null); next(); } }
			case 10 -> { if (ticks >= 40) { if (paired && approach(minecraft)) next(); else if (!paired || ticks > 400) { log("agent card via openAgentCard (no agent click: paired={} )", paired); mod.openAgentCard(threadId); stage = 13; } } }
			case 11 -> clickAgent(minecraft);
			case 12 -> { if (ready(minecraft) && ticks >= 120) { verifyRoute(minecraft, threadId, "agent-card"); next(); } }
			case 13 -> { if (ready(minecraft) && ticks >= 120) { if (!(minecraft.gui.screen() instanceof T3WebScreen s && s.mode() == T3WebScreen.Mode.CARD)) log("WARNING: no card on screen"); verifyRoute(minecraft, threadId, "agent-card"); next(); } }
			case 14 -> { if (ticks >= 30) { minecraft.gui.setScreen(null); next(); } }
			case 15 -> { if (ticks >= 40) { log("WEBSHOWCASE DONE"); minecraft.stop(); } }
			default -> {}
		}
	}

	/** Pairs this client with the local T3 when a link was given, so the office is populated; otherwise moves on. */
	private void pair(Minecraft minecraft) {
		if (pairLink == null) { next(); return; }
		if (!pairRequested) {
			pairRequested = true;
			mod.pairEnvironment(pairLink, "T3 Craft showcase", null, outcome -> log("pairing: {}", outcome.error() == null ? "ok" : outcome.error()));
			return;
		}
		if (mod.state().snapshot().connected()) { paired = true; log("paired; {} threads visible", mod.state().snapshot().threads().size()); next(); }
		else if (ticks > 600) { log("pairing did not connect in time; continuing unpaired"); next(); }
	}

	/** A fresh dev run has no world: create Minecraft's test world once, or reopen the one made last time. */
	private void enterWorld(Minecraft minecraft) {
		menuTicks++;
		var screen = minecraft.gui.screen();
		if (screen instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen create && menuTicks % 20 == 0) {
			create.children().stream().filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals("Create New World"))
				.map(c -> (net.minecraft.client.gui.components.Button) c).findFirst().ifPresent(button ->
					create.mouseClicked(new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false));
			return;
		}
		if (worldRequested || !(screen instanceof net.minecraft.client.gui.screens.TitleScreen) || menuTicks < 40) return;
		worldRequested = true;
		java.io.File[] saves = new java.io.File(minecraft.gameDirectory, "saves").listFiles(java.io.File::isDirectory);
		if (saves != null && saves.length > 0) minecraft.createWorldOpenFlows().openWorld(saves[0].getName(), () -> {});
		else net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.testWorld(minecraft, () -> {});
	}

	private void next() { stage++; ticks = 0; }

	private static boolean ready(Minecraft minecraft) {
		return minecraft.gui.screen() instanceof T3WebScreen screen && screen.hasFrame();
	}

	/** Clicks T3's own sidebar search and types a query: exercises mouse and text forwarding without touching threads. */
	private static void typeSearch(T3WebScreen screen) {
		WebViewport view = screen.viewportForTest();
		double x = view.guiX() + 60.0 / view.divisor(), y = view.guiY() + 75.0 / view.divisor();
		screen.mouseMoved(x, y);
		MouseButtonEvent click = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
		screen.mouseClicked(click, false);
		screen.mouseReleased(click);
		"minecraft".codePoints().forEach(c -> screen.charTyped(new CharacterEvent(c)));
	}

	/** The page must really hold the query in its focused input; otherwise the typing is retried, never faked. */
	private void verifyTyping(Minecraft minecraft) {
		if (ticks < 60 || !(minecraft.gui.screen() instanceof T3WebScreen screen)) return;
		String state = pageState(screen);
		log("after typing: {}", state);
		if (state.contains("\"value\":\"minecraft\"")) { log("search box holds the query; focus came from {}", clickedFocus ? "the forwarded click" : "a DOM focus"); shot(minecraft, "laptop-input"); next(); return; }
		if (++attempts >= 2) {
			// Known gap: the forwarded click does not always focus T3's search input in game (it does over plain CDP).
			// Focus it through the DOM so the typing path itself (charTyped -> Input.insertText) is still exercised for real.
			log("WARNING: the forwarded click did not focus T3's search box; focusing it via the DOM and typing again");
			try { screen.evalForShowcase("(() => { const i = document.querySelector('input[type=search]'); if (i) i.focus(); return !!i; })()"); } catch (Exception e) { log("focus failed: {}", e.toString()); }
			clickedFocus = false;
			"minecraft".codePoints().forEach(c -> screen.charTyped(new CharacterEvent(c)));
			if (attempts >= 3) { log("WARNING: typing never reached T3's search box"); shot(minecraft, "laptop-input-failed"); next(); return; }
			ticks = 0;
			return;
		}
		typeSearch(screen);
		ticks = 0;
	}

	/** Logs the route the page is on and takes the shot; the log line proves which thread the surface shows. */
	private void verifyRoute(Minecraft minecraft, String expectedThread, String label) {
		if (minecraft.gui.screen() instanceof T3WebScreen screen) {
			String state = pageState(screen);
			log("{} route check (expect {}): {}", label, expectedThread, state);
		}
		shot(minecraft, label);
	}

	private static String pageState(T3WebScreen screen) {
		try {
			var value = screen.evalForShowcase("(() => { const a = document.activeElement; return JSON.stringify({path: location.pathname, tag: a && a.tagName, value: a && a.value, title: document.title}); })()");
			return value == null ? "null" : value.getAsString();
		} catch (Exception e) {
			return "eval failed: " + e;
		}
	}

	/** Teleports the player next to the agent's character (trying each side of it) and looks at it. */
	private boolean approach(Minecraft minecraft) {
		Mannequin agent = mod.residents().mannequinFor(threadId);
		if (agent == null) return false;
		double[][] sides = {{1.6, 0}, {-1.6, 0}, {0, 1.6}, {0, -1.6}};
		double[] side = sides[offset % sides.length];
		ClientCommands.send("tp @s " + (agent.getX() + side[0]) + " " + Math.floor(agent.getY()) + " " + (agent.getZ() + side[1]));
		log("approaching agent for {} at {},{},{} from side {}", threadId, agent.getX(), agent.getY(), agent.getZ(), offset);
		return true;
	}

	/** A real use-key click on the agent, exactly what a player does; another side is tried when the ray misses. */
	private void clickAgent(Minecraft minecraft) {
		if (ticks < 20) return;
		Mannequin agent = mod.residents().mannequinFor(threadId);
		if (agent == null) { log("agent vanished; falling back to openAgentCard"); mod.openAgentCard(threadId); stage = 13; ticks = 0; return; }
		minecraft.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, agent.getEyePosition());
		if (!(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != agent) {
			if (ticks > 60) {
				if (++offset < 4) { approach(minecraft); ticks = 0; }
				else { log("agent ray never hit; falling back to openAgentCard"); mod.openAgentCard(threadId); stage = 13; ticks = 0; }
			}
			return;
		}
		log("agent in crosshair; right-clicking it");
		KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
		next();
	}

	private static void log(String message, Object... args) { T3CraftClient.LOGGER.info("WEBSHOWCASE " + message, args); }

	private static void shot(Minecraft minecraft, String label) {
		WebViewport view = minecraft.gui.screen() instanceof T3WebScreen screen ? screen.viewportForTest() : null;
		log("{} window {}x{} guiScale {} viewport {}", label, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(),
			minecraft.getWindow().getGuiScale(), view == null ? "-" : view.physicalWidth() + "x" + view.physicalHeight() + " css " + view.cssWidth() + "x" + view.cssHeight() + " dpr " + view.deviceScaleFactor());
		Screenshot.grab(minecraft.gameDirectory, minecraft.gameRenderer.mainRenderTarget(),
			message -> log("{} {}", label, message.getString()));
	}
}
