package dev.maxwellyoung.t3craft;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * The line above an office agent's head: one line, a status-coloured dot and the thread's short title.
 * <ul>
 * <li>Across the room it is only the dot, so a floor of agents reads as a row of status lights, not a wall of text.</li>
 * <li>Close by it is the dot and the title, cut to fit between two desks.</li>
 * <li>The agent under your crosshair gets its whole title and its status in words.</li>
 * <li>The one that needs you is always named, larger, on amber.</li>
 * </ul>
 * Drawn depth-tested at full brightness (the renderer mixin), so it is crisp, and hidden behind walls and floors.
 */
public final class AgentTag {
	private AgentTag() {}

	/** Titles are shown within this many blocks; beyond it, the dot alone. */
	static final double NAME_RANGE = 6.5;
	/** Font pixels a title may take at desk scale: two desks are two blocks apart. */
	static final int NAME_WIDTH = 86;
	static final int FULL_LENGTH = 28;

	/** What to draw: the text, its size relative to a vanilla name tag, the ARGB behind it, and how far it floats above the usual spot. */
	public record Look(Component text, float scale, int background, float lift) {}

	static int color(String status) {
		return switch (status == null ? "" : status) {
			case "WORKING" -> 0x5CB8FF;
			case "NEEDS YOU" -> 0xFFB52E;
			case "DONE" -> 0x62D586;
			case "ERROR" -> 0xFF6B5E;
			default -> 0x9AA0A6;
		};
	}

	static String word(String status) {
		return switch (status == null ? "" : status) {
			case "WORKING" -> "working";
			case "NEEDS YOU" -> "needs you";
			case "DONE" -> "done";
			case "ERROR" -> "hit an error";
			case "OFFLINE" -> "offline";
			default -> "idle";
		};
	}

	/**
	 * The tag for an agent named {@code name} (the entity's custom name, with or without its leading dot) whose
	 * description is {@code status}. {@code width} measures text in font pixels and {@code cut} trims it to a width.
	 * {@code quiet}: seated in the lounge, where seats are a block apart. Null draws nothing.
	 */
	public static Look look(String name, String status, double distance, boolean lookedAt, boolean quiet, boolean seated, float age,
		java.util.function.ToIntFunction<String> width, java.util.function.BiFunction<String, Integer, String> cut) {
		String title = name == null ? "" : name.replaceFirst("^●\\s*", "").strip();
		boolean helper = title.equals("Helper");
		boolean needs = "NEEDS YOU".equals(status), offline = "OFFLINE".equals(status);
		int accent = helper ? 0x9AA0A6 : color(status);
		float lift = seated ? 0.22f : 0.05f;
		MutableComponent dot = Component.literal("●").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(accent)));
		if (lookedAt) {
			// Under the crosshair: the whole title, and the status in words (a helper's line is what it is working on).
			String full = title.length() > FULL_LENGTH ? title.substring(0, FULL_LENGTH - 1).stripTrailing() + "…" : title;
			return new Look(dot.append(text(" " + full, 0xFFFFFF)).append(text("  " + (helper ? status : word(status)), needs ? accent : 0xB8BEC6)),
				1.0f, needs ? 0xD0402A00 : 0xC0101418, lift);
		}
		if (needs && distance < 32) {
			// Always named, a size up, bobbing gently: the one tag worth reading from across the floor.
			float bob = 0.035f * (float) Math.sin(age * 0.16f);
			return new Look(dot.append(text(" " + fit(title, NAME_WIDTH + 30, width, cut), 0xFFF3D6)), distance < NAME_RANGE ? 1.15f : 1.5f, 0xD0402A00, lift + 0.06f + bob);
		}
		if (quiet || distance >= NAME_RANGE) return distance < 32 ? new Look(dot, quiet && distance < NAME_RANGE ? 1.0f : 1.5f, 0x70101418, lift) : null;
		return new Look(dot.append(text(" " + fit(title, NAME_WIDTH, width, cut), offline ? 0x9AA0A6 : helper ? 0xC8CDD3 : 0xFFFFFF)), 0.8f, 0x90101418, lift);
	}

	/** The title cut to {@code max} font pixels, at a word where one is close, with an ellipsis. */
	static String fit(String title, int max, java.util.function.ToIntFunction<String> width, java.util.function.BiFunction<String, Integer, String> cut) {
		if (width.applyAsInt(title) <= max) return title;
		String head = cut.apply(title, max - width.applyAsInt("…"));
		int space = head.lastIndexOf(' ');
		if (space >= head.length() * 2 / 3) head = head.substring(0, space);
		return head.stripTrailing() + "…";
	}

	private static MutableComponent text(String text, int rgb) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
	}
}
