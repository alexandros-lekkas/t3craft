package dev.maxwellyoung.t3craft;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent replies as plain wrapped text in Minecraft's font, with only the lightest formatting:
 * bold and inline code. Headings become bold lines, lists keep a bullet, code blocks keep their
 * spacing in the code colour, and everything else is shown as it was written, so nothing is hidden.
 */
final class T3Markdown {
	/** One wrapped row; {@code kind} picks the spacing and colour. */
	record Line(FormattedCharSequence text, int indent, Kind kind) {
		static final Line GAP = new Line(FormattedCharSequence.EMPTY, 0, Kind.GAP);
	}

	enum Kind { TEXT, GAP, LABEL, STEPS }

	private static final Pattern LIST = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
	private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*)$");
	private static final Pattern RULE_LINE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
	private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
	private static final Pattern INLINE = Pattern.compile(
		"`([^`]+)`"                                   // code
			+ "|\\*\\*(.+?)\\*\\*|__(.+?)__"          // bold
			+ "|\\[([^\\]]+)]\\(([^)\\s]+)\\)");       // link: the label is shown, the address is not clickable here

	private T3Markdown() {
	}

	/** Appends the wrapped rows for one message body in {@code color}; code uses {@code codeColor}. */
	static void render(Font font, String markdown, int width, int color, int codeColor, List<Line> out) {
		if (markdown == null) return;
		boolean inCode = false;
		for (String raw : markdown.replace("\t", "    ").split("\n", -1)) {
			String trimmed = raw.strip();
			if (trimmed.startsWith("```")) {
				inCode = !inCode;
				continue;
			}
			if (inCode) {
				// Code keeps its spacing; long lines wrap rather than run off the panel.
				wrap(font, Component.literal(raw.isEmpty() ? " " : raw).withColor(codeColor), width - 6, 6, Kind.TEXT, out);
				continue;
			}
			if (trimmed.isEmpty()) {
				gap(out);
				continue;
			}
			Matcher heading = HEADING_LINE.matcher(trimmed);
			if (heading.matches()) {
				gap(out);
				wrap(font, inline(heading.group(2), color, codeColor).withStyle(Style.EMPTY.withBold(true)), width, 0, Kind.TEXT, out);
				continue;
			}
			if (RULE_LINE.matcher(trimmed).matches()) {
				gap(out);
				continue;
			}
			if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
				if (TABLE_DIVIDER.matcher(trimmed).matches()) continue;
				wrap(font, inline(String.join("   ", cells(trimmed)), color, codeColor), width, 0, Kind.TEXT, out);
				continue;
			}
			if (trimmed.startsWith(">")) trimmed = trimmed.replaceFirst("^>\\s?", "");
			Matcher list = LIST.matcher(raw);
			if (list.matches()) {
				int depth = Math.min(3, list.group(1).length() / 2);
				String token = list.group(2);
				String marker = Character.isDigit(token.charAt(0)) ? token.replace(")", ".") : "•";
				int indent = depth * 8;
				MutableComponent row = Component.literal(marker + " ").withColor(color).append(inline(list.group(3), color, codeColor));
				wrap(font, row, width - indent, indent, Kind.TEXT, out);
				continue;
			}
			wrap(font, inline(trimmed, color, codeColor), width, 0, Kind.TEXT, out);
		}
		while (!out.isEmpty() && out.getLast().kind() == Kind.GAP) out.removeLast();
	}

	/** Inline spans → one styled component. Unmatched markers stay as literal text. */
	static MutableComponent inline(String text, int color, int codeColor) {
		MutableComponent result = Component.empty();
		Matcher m = INLINE.matcher(text);
		int last = 0;
		while (m.find()) {
			if (m.start() > last) result.append(Component.literal(text.substring(last, m.start())).withColor(color));
			if (m.group(1) != null) {
				result.append(Component.literal(m.group(1)).withColor(codeColor));
			} else if (m.group(2) != null || m.group(3) != null) {
				result.append(inline(m.group(2) != null ? m.group(2) : m.group(3), color, codeColor).withStyle(Style.EMPTY.withBold(true)));
			} else {
				result.append(Component.literal(m.group(4)).withColor(color));
			}
			last = m.end();
		}
		if (last < text.length()) result.append(Component.literal(text.substring(last)).withColor(color));
		return result;
	}

	/** Cells of a "| a | b |" row: empty cells are kept, and {@code \|} is a literal pipe, not a divider. */
	static List<String> cells(String row) {
		String body = row.strip();
		if (body.startsWith("|")) body = body.substring(1);
		if (body.endsWith("|") && !body.endsWith("\\|")) body = body.substring(0, body.length() - 1);
		List<String> cells = new java.util.ArrayList<>();
		StringBuilder cell = new StringBuilder();
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') { cell.append('|'); i++; }
			else if (c == '|') { cells.add(cell.toString().strip()); cell.setLength(0); }
			else cell.append(c);
		}
		cells.add(cell.toString().strip());
		return cells;
	}

	static void wrap(Font font, Component text, int width, int indent, Kind kind, List<Line> out) {
		List<FormattedCharSequence> parts = font.split(text, Math.max(40, width));
		for (FormattedCharSequence part : parts) out.add(new Line(part, indent, kind));
	}

	private static void gap(List<Line> out) {
		if (!out.isEmpty() && out.getLast().kind() != Kind.GAP) out.add(Line.GAP);
	}
}
