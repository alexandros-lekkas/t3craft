package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * New T3 World: a name, a place, a game mode, one button. Vanilla widgets in a 200-px column so it
 * fits a GUI scale 5 window. Cheats are always on (your agents build with them) and structures on.
 */
final class T3WorldScreen extends Screen {
	/** A choice on the Place button: a curated seed, Surprise me, or your own seed. */
	record Place(T3Seeds.Seed seed, String label, String blurb) {
		static Place of(T3Seeds.Seed seed) { return new Place(seed, seed.name(), seed.blurb()); }
		static final Place SURPRISE = new Place(null, "Surprise me", "A random world. The office finds a flat spot.");
		static final Place CUSTOM = new Place(null, "Custom seed", "Your seed. The office finds a flat spot.");
		boolean custom() { return this == CUSTOM; }
	}

	static final int COLUMN = 200;
	private static final int GREY = 0xFFA0A0A0, WHITE = 0xFFFFFFFF;

	private final Screen parent;
	private final T3CraftClient mod;
	private EditBox name, customSeed;
	private Button cancel;
	private Place place;
	private boolean creative = true;
	private String error = "";
	private int top;

	T3WorldScreen(Screen parent, T3CraftClient mod) {
		super(Component.literal("New T3 World"));
		this.parent = parent;
		this.mod = mod;
		this.place = places().getFirst();
	}

	static List<Place> places() {
		List<Place> list = new ArrayList<>();
		for (T3Seeds.Seed seed : T3Seeds.all()) list.add(Place.of(seed));
		list.add(Place.SURPRISE);
		list.add(Place.CUSTOM);
		return list;
	}

	private int left() { return (width - COLUMN) / 2; }

	@Override
	protected void init() {
		int x = left();
		int rows = place.custom() ? 196 : 170;
		top = Math.max(8, (height - rows) / 2);
		name = new EditBox(font, x, top + 26, COLUMN, 20, name, Component.literal("World Name"));
		name.setMaxLength(32);
		if (name.getValue().isEmpty()) name.setValue(T3WorldCreator.DEFAULT_NAME);
		addRenderableWidget(name);
		addRenderableWidget(CycleButton.<Place>builder(p -> Component.literal(p.label()), place).withValues(places())
			.create(x, top + 52, COLUMN, 20, Component.literal("Place"), (button, value) -> { place = value; error = ""; rebuildKeepingName(); }));
		addRenderableWidget(CycleButton.<Boolean>builder(c -> Component.literal(c ? "Creative" : "Survival"), creative).withValues(List.of(true, false))
			.create(x, top + 88, COLUMN, 20, Component.literal("Game Mode"), (button, value) -> creative = value));
		int y = top + 124;
		if (place.custom()) {
			customSeed = new EditBox(font, x, y, COLUMN, 20, customSeed, Component.literal("Seed"));
			customSeed.setMaxLength(64);
			customSeed.setHint(Component.literal("Seed (number or text)").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			addRenderableWidget(customSeed);
			y += 26;
		} else {
			customSeed = null;
		}
		y += 16;
		addRenderableWidget(Button.builder(Component.literal("Create T3 World"), b -> create()).bounds(x, y, 118, 20).build());
		cancel = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + 122, y, 78, 20).build());
		setInitialFocus(place.custom() ? customSeed : name);
	}

	private void rebuildKeepingName() {
		rebuildWidgets();
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		// Enter creates from anywhere on the form, except when you've tabbed to Cancel: then it cancels.
		if (e.isConfirmation() && getFocused() != cancel) { create(); return true; }
		return super.keyPressed(e);
	}

	private void create() {
		error = "";
		try {
			T3WorldCreator.create(minecraft, parent, name.getValue(), place.seed(), customSeed == null ? null : customSeed.getValue(), creative, mod.floors());
			// The world is on its way: agent build is what it is for.
			mod.saveAgentBuildOn();
		} catch (Exception e) {
			T3Log.LOGGER.error("Could not create the T3 World", e);
			error = "Could not create the world: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int x = left();
		g.centeredText(font, title.getString(), width / 2, top, WHITE);
		g.text(font, "World Name", x, top + 15, GREY, false);
		g.text(font, place.blurb(), x, top + 75, GREY, false);
		g.text(font, creative ? "Fly and build freely. Peaceful, no mobs." : "Day and night, mobs, hunger. Inventory kept.", x, top + 111, GREY, false);
		int y = top + 124 + (place.custom() ? 26 : 0);
		g.text(font, "Cheats are on so your agents can build.", x, y + 2, GREY, false);
		if (!error.isEmpty()) g.text(font, T3Hud.ellipsize(font, error, width - 20), 10, height - 12, 0xFFFF6B6B, false);
	}

	/** For tests and the self-test: what Create would make. */
	Place placeForTest() { return place; }
	boolean creativeForTest() { return creative; }
	String nameForTest() { return name.getValue(); }
	/** Picks a place by its button label, as the self-test does; false if there is no such place. */
	boolean selectPlaceForTest(String label) {
		for (Place candidate : places()) {
			if (!candidate.label().equalsIgnoreCase(label)) continue;
			place = candidate; error = ""; rebuildKeepingName();
			return true;
		}
		return false;
	}

	static Screen open(Screen parent, T3CraftClient mod) {
		Minecraft minecraft = Minecraft.getInstance();
		List<T3WorldCreator.Existing> existing = T3WorldCreator.existing(minecraft);
		return existing.isEmpty() ? new T3WorldScreen(parent, mod) : new T3WorldHubScreen(parent, mod, existing.getFirst());
	}
}
