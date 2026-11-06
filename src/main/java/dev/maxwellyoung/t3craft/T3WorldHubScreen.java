package dev.maxwellyoung.t3craft;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;

/** You already have a T3 World: continue it (Enter) or start another. */
final class T3WorldHubScreen extends Screen {
	private final Screen parent;
	private final T3CraftClient mod;
	private final T3WorldCreator.Existing world;
	private int top;

	T3WorldHubScreen(Screen parent, T3CraftClient mod, T3WorldCreator.Existing world) {
		super(Component.literal("T3 World"));
		this.parent = parent;
		this.mod = mod;
		this.world = world;
	}

	@Override
	protected void init() {
		int x = (width - T3WorldScreen.COLUMN) / 2;
		top = Math.max(8, (height - 110) / 2);
		Button go = addRenderableWidget(Button.builder(Component.literal(T3Hud.ellipsize(font, "Continue: " + world.data().name, T3WorldScreen.COLUMN - 8)),
			b -> T3WorldCreator.open(minecraft, world.levelId(), this)).bounds(x, top + 24, T3WorldScreen.COLUMN, 20).build());
		addRenderableWidget(Button.builder(Component.literal("New T3 World"), b -> minecraft.gui.setScreen(new T3WorldScreen(parent, mod)))
			.bounds(x, top + 62, T3WorldScreen.COLUMN, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(x, top + 90, T3WorldScreen.COLUMN, 20).build());
		setInitialFocus(go);
	}

	static String subtitle(T3WorldCreator.Existing world, long now) {
		long ago = Math.max(0, now - world.lastPlayed());
		Duration d = Duration.ofMillis(ago);
		String when = d.toMinutes() < 1 ? "just now" : d.toHours() < 1 ? d.toMinutes() + " min ago" : d.toDays() < 1 ? d.toHours() + " h ago" : d.toDays() + " d ago";
		T3Seeds.Seed seed = T3Seeds.byId(world.data().seedId);
		String place = seed != null ? seed.name() : "Seed " + world.data().seed;
		return "Last played " + when + " · " + place + " · " + (world.data().creative ? "Creative" : "Survival");
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title.getString(), width / 2, top, 0xFFFFFFFF);
		g.centeredText(font, T3Hud.ellipsize(font, subtitle(world, System.currentTimeMillis()), width - 20), width / 2, top + 47, 0xFFA0A0A0);
	}
}
