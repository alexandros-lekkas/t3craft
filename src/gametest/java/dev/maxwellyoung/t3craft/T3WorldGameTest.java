package dev.maxwellyoung.t3craft;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;

/**
 * Client game test ({@code ./gradlew runClientGameTest}, or {@code scripts/mc gametest}): the title screen offers
 * T3 World, and creating one lands the player in a world with the office already built. Lives in the mod's package
 * so it can read package-private state the way the plain-JVM checks in {@code src/smoke} do.
 *
 * <p>Runs on the test thread: touch the game only through {@code context} (see the Fabric docs on client game
 * tests). The run is unpaired; nothing here talks to a T3 server.
 */
@SuppressWarnings("UnstableApiUsage")
public class T3WorldGameTest implements FabricClientGameTest {
	/** World generation, the office build and the first chunks, in ticks. */
	private static final int WORLD_TIMEOUT = 20 * 180;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		boolean offered = context.computeOnClient(mc -> mc.gui.screen().children().stream()
			.anyMatch(c -> c instanceof Button b && b.getMessage().getString().equals("T3 World")));
		if (!offered) throw new AssertionError("The title screen has no T3 World button");
		context.takeScreenshot("t3craft-title");

		// A fresh game directory has no T3 World yet, so the button opens the New T3 World form directly.
		context.clickScreenButton("T3 World");
		context.waitForScreen(T3WorldScreen.class);
		context.takeScreenshot("t3craft-new-world");
		context.clickScreenButton("Create T3 World");

		context.waitFor(mc -> mc.level != null && mc.player != null, WORLD_TIMEOUT);
		try {
			context.waitFor(mc -> mc.gui.screen() == null, 20 * 30);
		} catch (AssertionError e) {
			throw new AssertionError("Still on " + context.computeOnClient(mc -> String.valueOf(mc.gui.screen())) + " after entering the T3 World", e);
		}
		// Entering a T3 World adopts the office the integrated server built at the seed's site (no pairing needed).
		context.waitFor(mc -> T3CraftClient.get().officeFrame() != null, 20 * 30);
		context.waitTicks(60);
		BlockPos origin = context.computeOnClient(mc -> T3CraftClient.get().officeFrame().origin());
		int planks = context.computeOnClient(mc -> {
			int count = 0;
			for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-40, 0, -40), origin.offset(40, 12, 40)))
				if (mc.level.getBlockState(pos).is(BlockTags.PLANKS)) count++;
			return count;
		});
		if (planks < 50) throw new AssertionError("No office at " + origin.toShortString() + ": only " + planks + " plank blocks around it");
		context.takeScreenshot("t3craft-t3-world-arrival");

		// The runner expects every test to end on the title screen with no world open.
		Thread server = context.computeOnClient(mc -> mc.getSingleplayerServer().getRunningThread());
		context.runOnClient(mc -> {
			mc.level.disconnect(Component.translatable("menu.savingLevel"));
			mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
		});
		context.waitFor(mc -> mc.level == null && !server.isAlive(), 20 * 60);
		context.setScreen(TitleScreen::new);
		context.waitForScreen(TitleScreen.class);
	}
}
