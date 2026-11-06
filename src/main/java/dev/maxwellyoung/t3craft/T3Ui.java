package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared look of the two native screens: a vanilla container-style panel (light, bevelled, like the
 * inventory or advancements window) over a lightly dimmed world, dark text in the default font, a status
 * dot and a provider glyph per thread. Everything is in GUI pixels, so it follows the player's GUI scale.
 */
final class T3Ui {
	static final int PANEL = 0xFFC6C6C6;
	static final int PANEL_LIGHT = 0xFFFFFFFF;
	static final int PANEL_DARK = 0xFF555555;
	static final int PANEL_EDGE = 0xFF000000;
	/** Vanilla's container label colour. */
	static final int TEXT = 0xFF404040;
	static final int TITLE = 0xFF1C1C1C;
	static final int MUTED = 0xFF8C8C8C;
	static final int CODE = 0xFF245C8F;
	static final int WORKING = 0xFF2F7FE0;
	static final int NEEDS_YOU = 0xFFD9780A;
	static final int FAILED = 0xFFC93D3D;
	static final int IDLE = 0xFF8C8C8C;
	/** The world behind a screen: dimmed a little, never blurred, so it keeps reading as the world. */
	static final int DIM = 0x66000000;

	private static final FontDescription GLYPHS = new FontDescription.Resource(Identifier.fromNamespaceAndPath(T3CraftClient.MOD_ID, "provider_icons"));

	private T3Ui() {
	}

	/** A vanilla container window: black edge, light top-left bevel, dark bottom-right bevel, grey body. */
	static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		g.fill(x0 + 1, y0, x1 - 1, y0 + 1, PANEL_EDGE);
		g.fill(x0 + 1, y1 - 1, x1 - 1, y1, PANEL_EDGE);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, PANEL_EDGE);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, PANEL_EDGE);
		g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL);
		g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 2, PANEL_LIGHT);
		g.fill(x0 + 1, y0 + 1, x0 + 2, y1 - 2, PANEL_LIGHT);
		g.fill(x0 + 2, y1 - 2, x1 - 1, y1 - 1, PANEL_DARK);
		g.fill(x1 - 2, y0 + 2, x1 - 1, y1 - 1, PANEL_DARK);
	}

	/** The engraved line vanilla draws between panel sections. */
	static void dividerVertical(GuiGraphicsExtractor g, int x, int y0, int y1) {
		g.fill(x, y0, x + 1, y1, PANEL_DARK);
		g.fill(x + 1, y0, x + 2, y1, PANEL_LIGHT);
	}

	static void dividerHorizontal(GuiGraphicsExtractor g, int x0, int x1, int y) {
		g.fill(x0, y, x1, y + 1, PANEL_DARK);
		g.fill(x0, y + 1, x1, y + 2, PANEL_LIGHT);
	}

	static int statusColor(T3State.Status status, boolean online) {
		if (!online) return IDLE;
		return switch (status) {
			case WORKING -> WORKING;
			case NEEDS_YOU -> NEEDS_YOU;
			case ERROR -> FAILED;
			default -> IDLE;
		};
	}

	/** A 4 × 4 status dot with a one-pixel dark rim so it reads on the light panel. */
	static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 1, y - 1, x + 5, y + 5, 0xFF3A3A3A);
		g.fill(x, y, x + 4, y + 4, color);
	}

	/** "Working 2m 10s", "Needs you", "Done", "Idle", "Failed", or "Offline". */
	static String status(T3State.ThreadRow row, boolean online) {
		if (!online) return "Offline";
		String label = T3Hud.label(row.status());
		if (row.status() == T3State.Status.WORKING) {
			String elapsed = T3Hud.elapsed(row.workingSince());
			if (!elapsed.isEmpty()) label += " " + elapsed;
		}
		return label;
	}

	private static JsonObject icon(T3CraftClient mod, T3State.ThreadRow row) {
		String key = AgentAppearance.provider(mod.state().driverFor(row));
		return AgentAppearance.ICONS.getAsJsonObject("icons").getAsJsonObject("default".equals(key) ? "fallback" : key);
	}

	/** The provider's icon from the bundled glyph font (U+E000…), in its own colours. */
	static Component glyph(T3CraftClient mod, T3State.ThreadRow row) {
		String code = icon(mod, row).getAsJsonObject("glyph").get("color").getAsString();
		char glyph = (char) Integer.parseInt(code.substring(2), 16);
		return Component.literal(String.valueOf(glyph)).withStyle(Style.EMPTY.withFont(GLYPHS).withColor(0xFFFFFFFF));
	}

	/** "Claude", "Codex", … or "Agent" when T3 did not say. */
	static String provider(T3CraftClient mod, T3State.ThreadRow row) {
		String key = AgentAppearance.provider(mod.state().driverFor(row));
		return "default".equals(key) ? "Agent" : icon(mod, row).get("label").getAsString();
	}

	/** Entries that are the agent doing things (tool calls, commands), not the requests the player answers. */
	static int steps(T3State.Focus focus) {
		int steps = 0;
		for (T3Activity.Entry entry : focus.activity()) {
			String kind = entry.kind();
			if (kind.contains("approval") || kind.contains("user_input") || kind.contains("user-input")) continue;
			steps++;
		}
		return steps;
	}

	/**
	 * The conversation as wrapped rows: a muted "You" / provider label above each message, the text in plain
	 * form, and the agent's tool activity folded into one "… N steps" row before its reply.
	 */
	static List<T3Markdown.Line> conversation(Font font, String agent, T3State.Focus focus, int width) {
		List<T3Markdown.Line> lines = new ArrayList<>();
		List<T3State.Message> messages = focus.messages();
		int steps = steps(focus);
		int lastUser = -1;
		for (int i = 0; i < messages.size(); i++) if ("user".equals(messages.get(i).role())) lastUser = i;
		for (int i = 0; i < messages.size(); i++) {
			T3State.Message message = messages.get(i);
			boolean user = "user".equals(message.role());
			if (!lines.isEmpty()) lines.add(T3Markdown.Line.GAP);
			if (steps > 0 && !user && i > lastUser) {
				lines.add(stepsLine(font, steps));
				lines.add(T3Markdown.Line.GAP);
				steps = 0;
			}
			T3Markdown.wrap(font, Component.literal(user ? "You" : agent).withColor(MUTED), width, 0, T3Markdown.Kind.LABEL, lines);
			T3Markdown.render(font, message.text() + (message.streaming() ? " ▍" : ""), width, TEXT, CODE, lines);
		}
		if (steps > 0) {
			if (!lines.isEmpty()) lines.add(T3Markdown.Line.GAP);
			lines.add(stepsLine(font, steps));
		}
		return lines;
	}

	private static T3Markdown.Line stepsLine(Font font, int steps) {
		Component text = Component.literal("… " + steps + (steps == 1 ? " step" : " steps")).withColor(MUTED);
		return new T3Markdown.Line(font.split(text, 1000).getFirst(), 0, T3Markdown.Kind.STEPS);
	}

	/** Draws wrapped rows bottom-aligned inside [top, bottom), {@code scroll} rows up from the end. Returns the clamped scroll. */
	static int drawLines(GuiGraphicsExtractor g, Font font, List<T3Markdown.Line> lines, int x, int right, int top, int bottom, int scroll) {
		int gap = 4;
		// Rows are not all the same height (gaps are short), so lay out from the end until the area is full.
		int visibleHeight = bottom - top;
		int end = Math.max(0, lines.size() - scroll);
		int start = end;
		int used = 0;
		while (start > 0) {
			int h = lines.get(start - 1).kind() == T3Markdown.Kind.GAP ? gap : font.lineHeight;
			if (used + h > visibleHeight) break;
			used += h;
			start--;
		}
		// Reading back past the top: the oldest rows are the limit.
		if (start == 0 && end < lines.size()) {
			int extra = 0;
			while (end < lines.size()) {
				int h = lines.get(end).kind() == T3Markdown.Kind.GAP ? gap : font.lineHeight;
				if (used + h > visibleHeight) break;
				used += h;
				end++;
				extra++;
			}
			scroll = Math.max(0, scroll - extra);
		}
		g.enableScissor(x, top, right, bottom);
		int y = bottom - used;
		for (int i = start; i < end; i++) {
			T3Markdown.Line line = lines.get(i);
			if (line.kind() == T3Markdown.Kind.GAP) {
				y += gap;
				continue;
			}
			g.text(font, line.text(), x + line.indent(), y, TEXT, false);
			y += font.lineHeight;
		}
		g.disableScissor();
		return scroll;
	}
}
